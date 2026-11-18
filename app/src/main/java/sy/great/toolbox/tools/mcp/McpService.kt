package sy.great.toolbox.tools.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import sy.great.toolbox.MainActivity
import java.io.File

/**
 * MCP 前台服务。
 *
 * 为什么必须是 Service 而不是放在 Composable 里：
 *   放在 Composable 里时，只要离开页面或重组，
 *   remember 出来的 server 就会重建，端口断掉、客户端掉线。
 *   做成前台 Service 后：
 *     · 切界面不影响
 *     · 系统不会因内存紧张回收
 *     · 通知栏常驻「MCP 服务运行中」，一眼能看出在跑
 *
 * 停止方式：只能在页面上点「停止」，或杀掉 App。
 * 绝不自己退出（包括 startCommand 被重复调用）。
 */
class McpService : Service() {

    companion object {
        const val CH_ID = "csdemo_mcp"
        const val CH_NAME = "MCP 服务"
        const val NOTI_ID = 0x4D43  // 'MC'

        /** 全局唯一实例（页面通过它拿状态） */
        @Volatile
        var current: McpServer? = null
            private set

        @Volatile
        var running: Boolean = false
            private set

        /** 服务启动中/停止的锁，避免并发重复启动 */
        private val lock = Any()

        /** 工作目录（固定） */
        fun workDir(): File = File("/storage/emulated/0/ELF分析处/mcp")

        // ---------------- 端口配置 ----------------

        private const val PREF = "csdemo_mcp"
        private const val KEY_PORT = "local_port"
        private const val KEY_LAN_PORT = "lan_port"

        /** 本机端口（默认 8903，已校验在 1024~65535 内） */
        fun localPort(ctx: Context): Int {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val v = p.getInt(KEY_PORT, McpServer.PORT_LOCAL)
            return if (McpServer.isValidPort(v)) v else McpServer.PORT_LOCAL
        }

        /** 设置本机端口（非法值忽略，下次启动生效） */
        fun setLocalPort(ctx: Context, port: Int): Boolean {
            if (!McpServer.isValidPort(port)) return false
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putInt(KEY_PORT, port).apply()
            return true
        }

        /** 局域网端口（0 = 不开启） */
        fun lanPort(ctx: Context): Int {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            return p.getInt(KEY_LAN_PORT, McpServer.PORT_LAN)
        }

        fun setLanPort(ctx: Context, port: Int): Boolean {
            if (port != 0 && !McpServer.isValidPort(port)) return false
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putInt(KEY_LAN_PORT, port).apply()
            return true
        }

        // ---------------- token ----------------

        /**
         * 是否启用 token 认证。
         *
         * 默认关闭：用户明确要求“填地址就能用”，URL 不要再带 ?token=。
         * 开启后局域网也不会强制，仅作可选校验（见 McpServer 的 requireToken）。
         */
        fun tokenEnabled(ctx: Context): Boolean {
            return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean("token_enabled", false)
        }

        fun setTokenEnabled(ctx: Context, enabled: Boolean) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean("token_enabled", enabled).apply()
        }

        /** token 持久化（与页面共用） */
        fun token(ctx: Context): String {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val existing = p.getString("token", "") ?: ""
            if (existing.isNotEmpty()) return existing
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            val t = bytes.joinToString("") { "%02x".format(it) }
            p.edit().putString("token", t).apply()
            return t
        }

        /**
         * 实际传给 McpServer 的 token。
         *
         * 未启用认证时返回空字符串 → 服务端不再校验。
         */
        fun effectiveToken(ctx: Context): String =
            if (tokenEnabled(ctx)) token(ctx) else ""

        /** 重置 token（会重启服务） */
        fun resetToken(ctx: Context): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            val t = bytes.joinToString("") { "%02x".format(it) }
            ctx.getSharedPreferences("csdemo_mcp", Context.MODE_PRIVATE)
                .edit().putString("token", t).apply()
            // 用新 token 重启
            restart(ctx)
            return t
        }

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, McpService::class.java)
            if (action != null) i.action = action
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, McpService::class.java))
        }

        /** 重启服务（改端口 / 改 token 后用）
         *
         * 不能直接 stop() + start()：
         * stopService 是异步的，旧的 onDestroy 可能在新的 onStartCommand
         * 之后才跑，把刚起来的 server 又关了。
         * 改用 ACTION_RESTART：在 service 内部同步地先停旧 server 再起新的。
         */
        fun restart(ctx: Context) = start(ctx, ACTION_RESTART)

        const val ACTION_RESTART = "sy.great.toolbox.mcp.RESTART"
    }

    private var server: McpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isRestart = intent?.action == ACTION_RESTART

        synchronized(lock) {
            // 先发通知，必须在 5 秒内（否则系统会崩服务）
            startForegroundCompat()

            // 重启请求：先把旧 server 彻底停掉（同步）
            if (isRestart) {
                try { server?.stop() } catch (_: Throwable) {}
                server = null
                current = null
                running = false
            }

            // 没在跑就（重新）启动
            if (server == null || !running) {
                val dir = workDir()
                try {
                    if (!dir.exists()) dir.mkdirs()
                } catch (_: Throwable) {
                }

                val app = applicationContext

                // 旧实例先停干净
                try { current?.stop() } catch (_: Throwable) {}

                val s = McpServer(
                    workDir = dir,
                    token = effectiveToken(app),
                    localPort = localPort(app),
                    lanPort = lanPort(app),
                )
                s.tools = McpTools.all()
                if (s.start()) {
                    server = s
                    current = s
                    running = true
                    // 启动成功才拿 WakeLock（失败时没必要阻止休眠）
                    acquireWakeLock()
                    // 重新发一次通知：首次发时 current 还是 null，端口取的是默认值，
                    // 这里用真实端口刷新一次。
                    startForegroundCompat()
                } else {
                    // 启动失败：保留状态供页面展示错误，但不结束服务（方便直接重试）
                    current = s
                    running = false
                }
            }
        }

        // START_STICKY：被系统杀掉后自动重启。
        // 但用户手动 stopService 不会再起来（系统区分这两种情况）。
        return START_STICKY
    }

    override fun onDestroy() {
        synchronized(lock) {
            try {
                server?.stop()
            } catch (_: Throwable) {
            }
            server = null
            current = null
            running = false
        }
        releaseWakeLock()
        stopForegroundCompat()
        super.onDestroy()
    }

    // ---------------- 通知 ----------------

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CH_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, CH_NAME, NotificationManager.IMPORTANCE_LOW)
            )
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 端口从实际服务状态取，不再用常量（已支持自定义）
        val localPortNow = current?.state?.localPort ?: localPort(this)
        val localUrl = "http://127.0.0.1:$localPortNow" + McpServer.PATH

        // 多行通知：地址 + 工作区（与用户要求的格式一致）
        val bigText = buildString {
            append(localUrl)
            append("\n工作区目录:")
            append("\n")
            append(McpTools.workDirPath())
        }

        val n = Notification.Builder(this, CH_ID)
            .setContentTitle("MCP 服务运行中...")
            .setContentText(localUrl)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .setStyle(Notification.BigTextStyle().bigText(bigText))
            .build()

        startForeground(NOTI_ID, n)
    }

    // ---------------- WakeLock ----------------

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /**
     * 保持 CPU 唤醒。
     *
     * 没有它的话，App 切到后台后 CPU 会被降频/休眠，
     * 表现就是「请求延迟特别高」或「客户端超时断线」。
     * 用 PARTIAL_WAKE_LOCK：只保 CPU，不亮屏幕。
     */
    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val wl = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "csdemo:mcp-server"
            )
            wl.setReferenceCounted(false)
            // 不设超时，由服务生命周期控制（停止/销毁时释放）
            wl.acquire()
            wakeLock = wl
        } catch (_: Throwable) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {
        }
    }
}
