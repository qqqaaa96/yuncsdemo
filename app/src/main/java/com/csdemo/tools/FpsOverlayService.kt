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

        /** 长按触发录制所需的时长 */
        private const val LONG_PRESS_MS = 2000L

        /** 录制中窗口的橙色 */
        private val REC_COLOR = Color.parseColor("#E6E8792B")
        private val REC_STROKE = Color.parseColor("#66FFD08A")

        /**
         * 采集间隔（毫秒）。
         *
         * 毫秒级实时刷新（Scene 同款观感）：
         *   · 走 Shizuku 时是 binder 调用，开销小，可以跑得很密；
         *   · 走 su 时每次要起进程，最快也就百毫秒级；
         *     所以这里不写死很小的值，而是“读完立刻读下一次”+ 最短间隔保护。
         *
         * UI 实际上限是屏幕帧率（约 16ms），再快也看不到差别，
         * 所以最短间隔取 16ms（约 60 次/秒）。
         */
        private const val TICK_MIN_MS = 16L

        /** 读得慢的时候的兜底间隔：不至于把 CPU 拖满。 */
        private const val TICK_SLOW_MS = 200L

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

    /** 上一次贴到界面上的刷新率文本，值没变就不重复 setText */
    @Volatile
    private var lastRateText: String? = null

    private val tick = object : Runnable {
        override fun run() {
            tickScheduled.set(false)
            if (destroyed) return

            // 在主线程捕获展开状态，避免后台线程读可变字段
            val wantDetail = expanded
            // 明细（CPU/温度/内存）不需要毫秒级，每 5 轮刷一次即可，降低开销
            val wantDetailNow = wantDetail && (detailCounter++ % 5 == 0)

            worker.execute {
                val started = System.currentTimeMillis()

                // 注意：这里在 Runnable 的匿名对象内部，
                // `this` 指向 Runnable 而不是 Service，必须显式指明接收者。
                val svc = this@FpsOverlayService
                // 一次读取拿到「值 + 是否实测」，
                // 高频采样下不能分开调 isReal + currentHz（那是两次读）。
                val reading = DisplayRateReader.read(svc)
                // 名义值明确标注，不冒充实测值
                val rate = DisplayRateReader.format(reading.hz) +
                        if (reading.real) "" else "（名义）"
                val detail = if (wantDetailNow) readDetail() else ""

                handler.post {
                    if (destroyed) return@post
                    // 值没变就不动；录制中也照常刷新（只是背景是橙色）
                    if (rate != lastRateText) {
                        lastRateText = rate
                        tvRate?.text = rate
                    }
                    if (wantDetailNow && expanded && detail.isNotBlank()) {
                        tvDetail?.text = detail
                    }
                }

                // 读完立刻排下一轮：间隔 = max(最短间隔, 本轮耗时)
                //
                // 注意：这里不能写 handler.postDelayed(this, next)，
                // 因为此处处于 worker.execute 的 lambda 内部，
                // this 指向那个 Runnable 而不是 tick，
                // 会把“下一轮采集”排错对象、导致循环断掉。
                // 必须显式用 tickSelf 引用本对象。
                val cost = System.currentTimeMillis() - started
                val next = maxOf(TICK_MIN_MS, cost)
                handler.postDelayed(tickSelf, next)
            }
        }
    }

    /** 指向 tick 自身，供其内部在其它 lambda 里排队下一轮 */
    private val tickSelf: Runnable get() = tick

    /** 明细轮询计数 */
    private var detailCounter = 0

    /** 安排下一轮采集。now=true 表示立即补一次（如刷新率变化时） */
    private fun scheduleTick(now: Boolean = false) {
        if (destroyed) return
        if (!tickScheduled.compareAndSet(false, true)) return
        handler.removeCallbacks(tick)
        // 用最短间隔排下一轮；实际节拍由 tick 内部按“上一轮耗时”自适应。
        if (now) handler.post(tick) else handler.postDelayed(tick, TICK_MIN_MS)
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

    /**
     * 触摸交互：
     *   · 拖动     —— 改位置，松手保存
     *   · 短按     —— 切换大小形态
     *   · 长按 2 秒 —— 开始帧率录制（窗口变橙色 + 提示）
     *   · 录制中点击 —— 停止录制（不足 10 秒则提示不允许）
     */
    private fun attachTouch(v: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        // 长按计时：按下时起一个 2 秒任务，提前抬手/移动就取消
        var longPressFired = false
        val longPress = Runnable {
            if (destroyed) return@Runnable
            longPressFired = true
            startRecording()
        }

        v.setOnTouchListener { _, e ->
            val p = lp ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    longPressFired = false
                    // 只有未在录制时才允许长按开始录制
                    if (!FpsRecorder.recording) handler.postDelayed(longPress, LONG_PRESS_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(6f) || abs(dy) > dp(6f)) {
                        moved = true
                        // 一旦移动就不再算长按
                        handler.removeCallbacks(longPress)
                    }
                    if (moved) {
                        p.x = startX + dx.roundToInt()
                        p.y = startY + dy.roundToInt()
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    when {
                        // 已触发长按（录制已开始）：抬手不做事
                        longPressFired -> Unit
                        moved -> prefs?.edit()?.putInt(K_X, p.x)?.putInt(K_Y, p.y)?.apply()
                        FpsRecorder.recording -> stopRecordingWithGuard()
                        else -> toggleExpanded()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    true
                }

                else -> false
            }
        }
    }

    // ---------------- 录制 ----------------

    /** 开始录制：窗口变橙色 + Toast 提示 */
    private fun startRecording() {
        if (FpsRecorder.recording) return
        if (!FpsRecorder.start(this)) {
            toast("无法开始录制（请确认 Shizuku 已授权）")
            return
        }
        applyRecordingStyle(true)
        toast("帧率录制中")
    }

    /** 尝试停止；不足最短时长则拒绝并提示 */
    private fun stopRecordingWithGuard() {
        if (!FpsRecorder.recording) return
        if (!FpsRecorder.canStop()) {
            val left = ((FpsRecorder.MIN_DURATION_MS - FpsRecorder.elapsedMs()) / 1000L) + 1L
            toast("至少需录制 10 秒（还差 " + left + " 秒）")
            return
        }
        val list = FpsRecorder.stop()
        applyRecordingStyle(false)
        toast("已停止录制，共 " + list.size + " 个采样点")
    }

    /**
     * 录制中：只把窗口背景换成橙色，不改变文字内容。
     *
     * 用户明确要求：录制时仍然显示实时帧率，
     * 橙色本身就是“正在录制”的提示；
     * 开始与结束另外用系统 Toast 告知。
     */
    private fun applyRecordingStyle(on: Boolean) {
        val box = root ?: return
        box.background = GradientDrawable().apply {
            cornerRadius = dp(14f).toFloat()
            setColor(if (on) REC_COLOR else Color.parseColor("#E6202020"))
            setStroke(dp(1f).toInt(), if (on) REC_STROKE else Color.parseColor("#33FFFFFF"))
        }
        // 不动 tvRate：刷新率继续实时刷新
    }

    private fun toast(text: String) {
        try {
            android.widget.Toast.makeText(
                applicationContext, text, android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (_: Throwable) {
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
