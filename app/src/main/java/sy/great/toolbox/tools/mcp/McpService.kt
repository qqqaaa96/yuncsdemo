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

        /** token 持久化（与页面共用） */
        fun token(ctx: Context): String {
            val p = ctx.getSharedPreferences("csdemo_mcp", Context.MODE_PRIVATE)
            val existing = p.getString("token", "") ?: ""
            if (existing.isNotEmpty()) return existing
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            val t = bytes.joinToString("") { "%02x".format(it) }
            p.edit().putString("token", t).apply()
            return t
        }

        /** 重置 token（会重启服务） */
        fun resetToken(ctx: Context): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            val t = bytes.joinToString("") { "%02x".format(it) }
            ctx.getSharedPreferences("csdemo_mcp", Context.MODE_PRIVATE)
                .edit().putString("token", t).apply()
            // 用新 token 重启
            stop(ctx)
            start(ctx)
            return t
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, McpService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, McpService::class.java))
        }
    }

    private var server: McpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lock) {
            if (server == null) {
                // 先发通知，必须在 5 秒内（否则系统会崩服务）
                startForegroundCompat()

                val dir = workDir()
                try {
                    if (!dir.exists()) dir.mkdirs()
                } catch (_: Throwable) {
                }

                val s = McpServer(dir, token(applicationContext))
                s.tools = McpTools.all()
                if (s.start()) {
                    server = s
                    current = s
                    running = true
                } else {
                    // 启动失败（端口被占）：撤销通知并结束
                    current = s  // 保留状态供页面展示错误
                    running = false
                    stopForegroundCompat()
                    stopSelf()
                    return START_NOT_STICKY
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

        val s = current
        val local = "127.0.0.1:" + McpServer.PORT_LOCAL
        val lan = if (s?.state?.lanAddress?.isNotBlank() == true)
            s.state.lanAddress + ":" + McpServer.PORT_LAN
        else
            "局域网 " + McpServer.PORT_LAN

        val n = Notification.Builder(this, CH_ID)
            .setContentTitle("MCP 服务运行中")
            .setContentText("$local · $lan · 工具 ${McpTools.all().size} 个")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

        startForeground(NOTI_ID, n)
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
