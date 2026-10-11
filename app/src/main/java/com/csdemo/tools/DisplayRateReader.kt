package com.csdemo.tools

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import java.util.Locale

/**
 * 屏幕刷新率读取。
 *
 * 数据源与读取思路参考 Scene（com.omarea.library.shell.FpsUtils）：
 *   Scene 不读 Display.refreshRate（那是固定名义值，不会跳动），
 *   而是读驱动层的实测帧率节点，所以能做到实时 + 带小数。
 *
 * 四个 sys 节点（按顺序探测，哪个存在用哪个）：
 *   1) /sys/class/drm/sde-crtc-0/measured_fps
 *   2) /sys/devices/platform/soc/ae00000.qcom,mdss_mdp/drm/card0/card0-sde-crtc-0/measured_fps
 *   3) /sys/class/graphics/fb0/measured_fps
 *   4) /sys/kernel/fpsgo/fstb/fpsgo_status     （联发科，需按表头找列）
 *
 * 都不可用时：
 *   5) service call SurfaceFlinger 1013        （binder 兑底）
 *
 * 最后才回退到 Display.refreshRate（名义值，会标注）。
 *
 * 权限：sys 节点通常需 root 或 shell。root 与 Shizuku 两条路
 * 执行的是同一条命令，只是身份不同，所以准确率一致。
 */
object DisplayRateReader {

    // ---------- 数据源 ----------

    private const val P_DRM_SDE = "/sys/class/drm/sde-crtc-0/measured_fps"
    private const val P_DRM_OLD = "/sys/devices/platform/soc/ae00000.qcom,mdss_mdp/drm/card0/card0-sde-crtc-0/measured_fps"
    private const val P_FB0 = "/sys/class/graphics/fb0/measured_fps"
    private const val P_FPSGO = "/sys/kernel/fpsgo/fstb/fpsgo_status"

    private val MEASURED_PATHS = listOf(P_DRM_SDE, P_DRM_OLD, P_FB0)

    /** 已探测出的可用路径；null 表示还没探过 */
    @Volatile
    private var cachedPath: String? = null

    /** 已探测过一轮（避免每帧都重新 try 不存在的路径） */
    @Volatile
    private var probed = false

    // ---------- 对外 ----------

    /** 一次读取的结果：值 + 是否为驱动实测（否则为名义值） */
    data class Reading(val hz: Float?, val real: Boolean)

    /**
     * 读一次。
     *
     * 毫秒级采样下本方法会被高频调用，所以：
     *   · 不做重复的“值/真假”两次读取，一次返回两者；
     *   · 路径一旦探明就缓存，后续只发一条 cat。
     *
     * 阻塞调用，必须在 IO 线程使用。
     */
    fun read(ctx: Context): Reading {
        realHz(ctx)?.let { return Reading(it, true) }
        return Reading(nominalHz(ctx), false)
    }

    /** 便捷：只要值 */
    fun currentHz(ctx: Context): Float? = read(ctx).hz

    /** 便捷：只要真假（仅供应在不关心数值时使用，会多读一次） */
    fun isReal(ctx: Context): Boolean = read(ctx).real

    // ---------- 真实值读取 ----------

    private fun realHz(ctx: Context): Float? {
        if (!canShell(ctx)) return null

        // 已缓存可用路径：直接用
        cachedPath?.let { p ->
            parseByPath(run(ctx, "cat $p"), p)?.let { return it }
            // 缓存失效（如节点消失），重新探测
            cachedPath = null
            probed = false
        }

        if (!probed) {
            // 按序探测 measured_fps 类节点
            for (p in MEASURED_PATHS) {
                if (run(ctx, "ls $p").isNotBlank()) {
                    val v = parseByPath(run(ctx, "cat $p"), p)
                    if (v != null) {
                        cachedPath = p
                        probed = true
                        return v
                    }
                }
            }
            // fpsgo（需按表头找列）
            if (run(ctx, "ls $P_FPSGO").isNotBlank()) {
                val v = parseFpsgo(run(ctx, "cat $P_FPSGO"))
                if (v != null) {
                    cachedPath = P_FPSGO
                    probed = true
                    return v
                }
            }
            probed = true
        }

        // 兑底：binder 调用
        return parseSurfaceFlinger(run(ctx, "service call SurfaceFlinger 1013"))
    }

    /** 按不同节点的格式解析 */
    private fun parseByPath(raw: String, path: String): Float? {
        if (raw.isBlank()) return null
        if (path == P_FPSGO) return parseFpsgo(raw)

        // measured_fps 类：按空白切分，取第 2 段（与 Scene 的 subStrFun 一致）
        val parts = raw.trim().split(Regex("[\\s]+"))
        val candidate = if (parts.size > 1) parts[1] else parts.getOrNull(0)
        val v = candidate?.toFloatOrNull()
        return v?.takeIf { it.isFinite() && it in 1f..300f }
    }

    /**
     * 联发科 fpsgo_status：表格形式。
     *
     * 第 0 行是表头，用空白切分后找 currentFPS 的列号；
     * 后续行取同一列的值（与 Scene 的 currentFpsLegend 一致）。
     */
    private fun parseFpsgo(raw: String): Float? {
        if (raw.isBlank()) return null
        val lines = raw.split("\n")
        if (lines.isEmpty()) return null
        val header = lines[0].trim().split(Regex("[\\s]+"))
        val idx = header.indexOfFirst { it.contains("currentFPS", ignoreCase = true) }
        if (idx < 0) return null
        for (i in 1 until lines.size) {
            val cols = lines[i].trim().split(Regex("[\\s]+"))
            val v = cols.getOrNull(idx)?.toFloatOrNull()
            if (v != null && v.isFinite() && v in 1f..300f) return v
        }
        return null
    }

    /**
     * service call SurfaceFlinger 1013 的兑底解析。
     *
     * 返回是 binder Parcel 的十六进制转储，不同版本格式不同，
     * 这里只做“暴搜小数”的保守处理：找出所有形如 0x.... 的字段
     * 无法可靠还原时返回 null，不编造数值。
     */
    private fun parseSurfaceFlinger(raw: String): Float? {
        if (raw.isBlank()) return null
        // 明确失败的不当成数据
        if (raw.contains("Error", ignoreCase = true) || raw.contains("Parcel", ignoreCase = true)) return null

        // Parcel 里刷新率常见以 float 十六进制（0000xxxx）体现，
        // 但字段偏移随版本变化，无法仅凭文本可靠定位 → 不猜。
        // 仅在输出里直出现形如 “refreshRate=59.94” 这种带标签的值时才采用。
        Regex("refreshRate=([0-9.]+)").find(raw)?.let { m ->
            m.groupValues.getOrNull(1)?.toFloatOrNull()?.let {
                if (it.isFinite() && it in 1f..300f) return it
            }
        }
        return null
    }

    // ---------- 名义值 ----------

    private fun nominalHz(ctx: Context): Float? = try {
        defaultDisplay(ctx)?.refreshRate?.takeIf { it.isFinite() && it > 0f }
    } catch (_: Throwable) {
        null
    }

    // ---------- 执行（同一条命令，两种身份） ----------

    /**
     * 本功能只走 ADB（Shizuku），不用 su。
     *
     * 原因：毫秒级刷新要求单次读取极快。
     *   · Shizuku 通过已绑定的 UserService 走 binder，
     *     命令在常驻 shell 进程里执行，开销小；
     *   · su 每次都要 fork 一个新进程，启动就是几十到几百毫秒，
     *     在毫秒级采样下会把 CPU 吃满，拿不到实时效果。
     */
    private fun canShell(ctx: Context): Boolean =
        try {
            AdbShell.granted()
        } catch (_: Throwable) {
            false
        }

    /** 以 shell（Shizuku）身份跑一条命令 */
    private fun run(ctx: Context, cmd: String): String = try {
        if (AdbShell.granted()) {
            AdbShell.exec(ctx.applicationContext, cmd)?.out.orEmpty()
        } else ""
    } catch (_: Throwable) {
        ""
    }

    // ---------- 格式化 ----------

    /** 格式化为 “119.88 Hz”；无效时 “-- Hz” */
    fun format(hz: Float?): String =
        if (hz == null || !hz.isFinite() || hz <= 0f) "-- Hz"
        else String.format(Locale.US, "%.2f Hz", hz)

    private fun defaultDisplay(ctx: Context): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dm = ctx.getSystemService(DisplayManager::class.java)
            dm?.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            @Suppress("DEPRECATION")
            (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        }
}
