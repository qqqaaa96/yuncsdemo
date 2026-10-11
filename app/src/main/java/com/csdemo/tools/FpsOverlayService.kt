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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 刷新率悬浮窗服务（系统级，Scene 同款交互）。
 *
 * 为什么要前台服务：
 *   悬浮窗要在切到其它应用后仍可见，如果只靠 Activity 里的 View 会被回收。
 *   所以用前台服务持有 WindowManager 的悬浮 View，并挂一个常驻通知。
 *
 * 交互（按用户要求）：
 *   · 小形态：只显示“120 Hz”
 *   · 点一下 → 大形态：刷新率 + CPU 频率 + 温度 + 内存
 *   · 再点一下 → 缩回小形态
 *   · 拖动可改位置（按下移动，松手保存）
 *
 * 数据读取：
 *   · 刷新率：Display API（无需权限）
 *   · CPU / 温度：复用 RootTune（优先直读 /sys，不行再 su）
 *   · 读不到就显示 “--”，绝不造假
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

    /** 每 1000ms 刷新一次数据 */
    private val tick = object : Runnable {
        override fun run() {
            refreshViews()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREF, Context.MODE_PRIVATE)
        expanded = prefs?.getBoolean(K_EXPANDED, false) ?: false
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundCompat()
        addOverlay()
        running = true
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(tick)
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
        val n = Notification.Builder(this, CH_ID)
            .setContentTitle("刷新率悬浮窗运行中")
            .setContentText("点击回到应用")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(NOTI_ID, n)
    }

    // ---------------- 悬浮窗 ----------------

    private fun addOverlay() {
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
        root = box
        runCatching { wm.addView(box, p) }
        refreshViews()
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
        refreshViews()
    }

    // ---------------- 数据 ----------------

    private fun refreshViews() {
        tvRate?.text = readRefreshRateText()
        if (expanded) tvDetail?.text = readDetailText()
    }

    /** 当前刷新率。读不到就 -- Hz。 */
    private fun readRefreshRateText(): String {
        val hz = try {
            val wmSvc = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wmSvc.defaultDisplay.refreshRate
        } catch (e: Throwable) {
            null
        }
        return if (hz == null || hz <= 0f) "-- Hz" else hz.roundToInt().toString() + " Hz"
    }

    /** 大形态的明细。任何一项读不到都显示 --，不造假。 */
    private fun readDetailText(): String {
        val sb = StringBuilder()

        // CPU：取 core0 的当前频率（kHz → MHz）
        val cpuText = try {
            val cores = RootTune.cores()
            val f = RootTune.freqOf(cores.firstOrNull() ?: 0)
            // 用 current（当前频率），不是 currentMin（最小限制）
            val cur = f.current
            if (cores.isNotEmpty() && cur > 0) {
                cores.size.toString() + " 核 · " + (cur / 1000) + " MHz"
            } else "--"
        } catch (e: Throwable) {
            "--"
        }
        sb.append(cpuText)

        // 温度：取第一个有效温区
        val tempText = try {
            val z = RootTune.thermalZones().firstOrNull { it.temp > 0.0 }
            if (z != null) String.format("%.1f", z.temp) + " °C" else "--"
        } catch (e: Throwable) {
            "--"
        }
        sb.append('\n').append(tempText)

        // 内存
        val memText = try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val used = (mi.totalMem - mi.availMem) / 1024.0 / 1024.0 / 1024.0
            String.format("%.1f G", used)
        } catch (e: Throwable) {
            "--"
        }
        sb.append('\n').append(memText)

        return sb.toString()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
}
