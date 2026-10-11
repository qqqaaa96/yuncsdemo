package com.csdemo.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.csdemo.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 刷新率悬浮窗服务（系统级，Scene 同款交互）。
 *
 * 职责（只做这些）：
 *   · 悬浮窗的创建 / 拖动 / 点击展开收起
 *   · 前台通知与生命周期
 *   · 按固定节拍请数据、把结果贴到 View 上
 *
 * 数据读取全部委托给：
 *   · DisplayRateReader   —— 屏幕刷新率（Float 精度）
 *   · DeviceMetricsReader —— CPU / 温度 / 内存
 *
 * 关键修正（相对早期版本）：
 *   1) 不再用正则从 dumpsys 里抓 fps 充当刷新率
 *   2) 刷新率不再缓存两秒（缓存会掩盖刷新率切换）
 *   3) 采集改为“上一轮结束再排下一轮”，不堆积任务
 *   4) addView 失败则不进入运行状态，并撤销前台通知
 *   5) 后台线程不再直读可变的 expanded
 */
class FpsOverlayService : Service() {

    companion object {
        private const val CH_ID = "csdemo_fps_overlay"
        private const val CH_NAME = "刷新率悬浮窗"
        private const val NOTI_ID = 0x5F0F

        private const val PREF = "csdemo_fps_overlay"
        private const val K_X = "overlay_x"
        private const val K_Y = "overlay_y"
        private const val K_EXPANDED = "overlay_expanded"

        /** 采集间隔（毫秒）。刷新率变化不需要每秒看，2 秒足够且省电。 */
        private const val TICK_MS = 2000L

        /** 悬浮窗是否正在运行（供设置页显示开关状态） */
        @Volatile
        var running: Boolean = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, FpsOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FpsOverlayService::class.java))
        }

        /** 是否已获得“显示在其他应用上层”权限 */
        fun canDraw(ctx: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx)
            else true
    }

    private var prefs: SharedPreferences? = null
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null

    private var tvRate: TextView? = null
    private var tvDetail: TextView? = null

    private var expanded = false
    private val handler = Handler(Looper.getMainLooper())

    /** 单线程：保证同一时间只有一个采集任务在跑 */
    private val worker: java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor(object : java.util.concurrent.ThreadFactory {
            override fun newThread(r: Runnable): Thread =
                Thread(r, "fps-overlay-worker").apply { isDaemon = true }
        })

    /** 防止重复排队 */
    private val tickScheduled = AtomicBoolean(false)

    /** 服务是否已销毁（用于丢弃晚到的回调） */
    @Volatile
    private var destroyed = false

    /** 监听显示模式变化（刷新率切换），立即重读一次 */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == android.view.Display.DEFAULT_DISPLAY) scheduleTick(now = true)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            tickScheduled.set(false)
            if (destroyed) return

            // 在主线程捕获展开状态，避免后台线程读可变字段
            val wantDetail = expanded

            worker.execute {
                // 注意：这里在 Runnable 的匿名对象内部，
                // `this` 指向 Runnable 而不是 Service，会报
                //   Argument type mismatch: actual type is '<anonymous>', but 'Context' was expected.
                // 必须用 this@FpsOverlayService 指明接收者。
                val rate = DisplayRateReader.format(
                    DisplayRateReader.currentHz(this@FpsOverlayService)
                )
                val detail = if (wantDetail) readDetail() else ""
                handler.post {
                    if (destroyed) return@post
                    tvRate?.text = rate
                    // 只有展开状态没变时才贴明细，避免刚收起又被写回
                    if (wantDetail && expanded) tvDetail?.text = detail
                }
            }
        }
    }

    /** 安排下一轮采集。now=true 表示立即补一次（如刷新率变化时） */
    private fun scheduleTick(now: Boolean = false) {
        if (destroyed) return
        if (!tickScheduled.compareAndSet(false, true)) return
        handler.removeCallbacks(tick)
        if (now) handler.post(tick) else handler.postDelayed(tick, TICK_MS)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // 没有悬浮窗权限就不该启动：直接停掉，避免“显示运行中但其实没窗口”。
        if (!canDraw(this)) {
            stopSelf()
            return
        }

        prefs = getSharedPreferences(PREF, Context.MODE_PRIVATE)
        expanded = prefs?.getBoolean(K_EXPANDED, false) ?: false
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        startForegroundCompat()

        if (!addOverlay()) {
            // 加窗失败：撤销通知并结束，running 保持 false。
            stopForegroundCompat()
            stopSelf()
            return
        }

        running = true

        // 监听刷新率切换：DisplayManager 在 API 17+ 可用。
        try {
            val dm = getSystemService(DisplayManager::class.java)
            dm?.registerDisplayListener(displayListener, handler)
        } catch (_: Throwable) {
        }

        scheduleTick(now = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(tick)
        tickScheduled.set(false)
        try {
            val dm = getSystemService(DisplayManager::class.java)
            dm?.unregisterDisplayListener(displayListener)
        } catch (_: Throwable) {
        }
        worker.shutdownNow()
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        running = false
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

        // 分版本构造：带渠道 ID 的构造方法从 API 26 才有。
        // 当前 minSdk=33，实际不会走到 else 分支，但写全以免日后降 minSdk 时崩。
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CH_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val n = builder
            .setContentTitle("刷新率悬浮窗运行中")
            .setContentText("点击回到应用")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pi)
            .setOngoing(true)
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

    // ---------------- 悬浮窗 ----------------

    /**
     * 创建并添加悬浮窗。
     *
     * 只有 addView 真的成功才返回 true；
     * 失败时把已创建的 View 清掉，不留半成品状态。
     */
    private fun addOverlay(): Boolean {
        if (!canDraw(this)) return false

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = prefs?.getInt(K_X, 40) ?: 40
        p.y = prefs?.getInt(K_Y, 220) ?: 220
        lp = p

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#E6202020"))
                setStroke(dp(1f).toInt(), Color.parseColor("#33FFFFFF"))
            }
        }

        tvRate = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            text = "-- Hz"
        }
        box.addView(tvRate)

        tvDetail = TextView(this).apply {
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 10f
            text = ""
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        box.addView(tvDetail)

        attachTouch(box)

        return try {
            wm.addView(box, p)
            root = box
            true
        } catch (_: Throwable) {
            root = null
            false
        }
    }

    /** 点击切换大小形态；拖动改位置 */
    private fun attachTouch(v: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            val p = lp ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(6f) || abs(dy) > dp(6f)) moved = true
                    if (moved) {
                        p.x = startX + dx.roundToInt()
                        p.y = startY + dy.roundToInt()
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs?.edit()?.putInt(K_X, p.x)?.putInt(K_Y, p.y)?.apply()
                    } else {
                        // 没移动 → 视为点击：切换大小
                        toggleExpanded()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleExpanded() {
        expanded = !expanded
        prefs?.edit()?.putBoolean(K_EXPANDED, expanded)?.apply()
        tvDetail?.visibility = if (expanded) View.VISIBLE else View.GONE
        // 展开时立即补一次明细，不用等下一个节拍
        scheduleTick(now = true)
    }

    // ---------------- 数据（在后台线程调用） ----------------

    private fun readDetail(): String {
        val cpu = DeviceMetricsReader.cpuText() ?: "--"
        val temp = DeviceMetricsReader.tempText() ?: "--"
        val mem = DeviceMetricsReader.memText(applicationContext) ?: "--"
        return cpu + "\n" + temp + "\n" + mem
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
}
