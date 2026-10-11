package com.csdemo.tools

import android.app.ActivityManager
import android.content.Context
import java.util.Locale

/**
 * 设备指标读取：CPU 频率 / 温度 / 内存。
 *
 * 设计原则：宁可标清含义，也不给错的数据。
 *   · CPU：默认取 CPU0，并在文案里写明是 CPU0，不假装是“整体频率”
 *   · 温度：按温区 type 识别 CPU 类温区；识别不到时标注“传感器温度”
 *   · 内存：明确标“系统已用”，不冒充“本应用占用”
 *
 * 所有读取都可能需要 root；读不到返回 null，由调用方显示 --。
 */
object DeviceMetricsReader {

    /** CPU 频率文案，形如 “CPU0 2400 MHz”；读不到返回 null */
    fun cpuText(): String? = try {
        val cores = RootTune.cores()
        if (cores.isEmpty()) return null
        val core = cores.first()
        val f = RootTune.freqOf(core)
        val cur = f.current
        if (cur <= 0L) null else "CPU" + core + " " + (cur / 1000) + " MHz"
    } catch (_: Throwable) {
        null
    }

    /**
     * 温度文案。
     *
     * 优先找 type 里包含 cpu / soc / ap / tsens 的温区（各厂商命名不同），
     * 找不到时才退到“第一个有效温区”，并在文案里标明是传感器温度，
     * 不能因为某个温区数值高就断言它是 CPU。
     */
    fun tempText(): String? = try {
        val zones = RootTune.thermalZones().filter { it.temp > 0.0 }
        if (zones.isEmpty()) return null

        val cpuLike = zones.firstOrNull { z ->
            val t = z.type.lowercase()
            t.contains("cpu") || t.contains("soc") ||
                    t.contains("ap") || t.contains("tsens")
        }

        if (cpuLike != null) {
            String.format(Locale.US, "%s %.1f °C", cpuLike.type, cpuLike.temp)
        } else {
            val z = zones.first()
            String.format(Locale.US, "%s %.1f °C（传感器）", z.type, z.temp)
        }
    } catch (_: Throwable) {
        null
    }

    /** 系统已用内存文案，形如 “系统已用 4.2 G” */
    fun memText(ctx: Context): String? = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val used = (mi.totalMem - mi.availMem) / 1024.0 / 1024.0 / 1024.0
        String.format(Locale.US, "系统已用 %.1f G", used)
    } catch (_: Throwable) {
        null
    }
}
