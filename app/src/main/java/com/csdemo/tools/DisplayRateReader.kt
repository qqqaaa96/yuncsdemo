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
 * 为什么不用 dumpsys SurfaceFlinger：
 *   activeMode 里的 fps 是“显示模式的标称值”，
 *   与系统报告给应用的 refreshRate 同源，且解析依赖 ROM 输出格式，
 *   用正则从几 MB 文本里抓 fps= 既不必要也不可靠。
 *
 * 这里直接取 Display.refreshRate，并保留 Float 精度：
 *   该值可能带小数（如 119.88 / 59.94），四舍五入到整数会丢失信息。
 *
 * 注意：它仍然是“系统报告的刷新率”，不等于游戏实际渲染帧率。
 */
object DisplayRateReader {

    /**
     * 当前默认显示器的刷新率（Hz）。
     * 取不到或非法时返回 null。
     */
    fun currentHz(ctx: Context): Float? = try {
        val d = defaultDisplay(ctx)
        d?.refreshRate?.takeIf { it.isFinite() && it > 0f }
    } catch (_: Throwable) {
        null
    }

    /**
     * 设备支持的所有刷新率档位（升序，已去重）。
     * 取不到时返回空列表。
     */
    fun supportedHz(ctx: Context): List<Float> = try {
        val d = defaultDisplay(ctx) ?: return emptyList()
        d.supportedModes
            .map { it.refreshRate }
            .filter { it.isFinite() && it > 0f }
            .distinct()
            .sorted()
    } catch (_: Throwable) {
        emptyList()
    }

    /** 格式化为“119.88 Hz”；无效时 “-- Hz” */
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
