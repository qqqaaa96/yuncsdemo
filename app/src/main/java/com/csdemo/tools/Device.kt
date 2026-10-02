package com.csdemo.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 硬件与系统信息采集。
 */
object Device {

    fun cpuInfo(): String {
        val text = Shell.readFile("/proc/cpuinfo").orEmpty()
        val model = Regex("Hardware\\s*:\\s*(.*)").find(text)?.groupValues?.get(1)?.trim()
        val cores = Runtime.getRuntime().availableProcessors()
        val freq = maxCpuFreq()
        val lines = ArrayList<String>()
        if (model != null) lines.add("型号: " + model)
        lines.add("核心数: " + cores)
        if (freq != null) lines.add("最大频率: " + (freq / 1000) + " MHz")
        return lines.joinToString("\n")
    }

    private fun maxCpuFreq(): Long? {
        return try {
            var max = 0L
            for (i in 0 until Runtime.getRuntime().availableProcessors()) {
                val f = java.io.File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq")
                if (f.exists()) {
                    val v = f.readText().trim().toLongOrNull() ?: 0L
                    if (v > max) max = v
                }
            }
            if (max > 0) max else null
        } catch (e: Exception) {
            null
        }
    }

    fun memory(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return "总内存: " + fmt(mi.totalMem) + "\n可用内存: " + fmt(mi.availMem) +
                "\n低内存状态: " + mi.lowMemory
    }

    fun storage(): String {
        val d = Environment.getDataDirectory()
        val sf = StatFs(d.path)
        val total = sf.blockCountLong * sf.blockSizeLong
        val free = sf.availableBlocksLong * sf.blockSizeLong
        return "内部存储总量: " + fmt(total) + "\n可用空间: " + fmt(free)
    }

    @Suppress("DEPRECATION")
    fun screen(ctx: Context): String {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        val refresh = try {
            wm.defaultDisplay.refreshRate.toInt().toString() + " Hz"
        } catch (e: Exception) {
            "未知"
        }
        val inch = if (dm.xdpi > 0f && dm.ydpi > 0f) {
            Math.sqrt(
                (dm.widthPixels.toDouble() / dm.xdpi).let { it * it } +
                        (dm.heightPixels.toDouble() / dm.ydpi).let { it * it }
            )
        } else 0.0
        val inchText = if (inch > 0.0) String.format(Locale.US, "%.2f", inch) else "未知"
        return "分辨率: " + dm.widthPixels + " x " + dm.heightPixels +
                "\n密度: " + dm.densityDpi + " dpi" +
                "\n刷新率: " + refresh +
                "\n尺寸: " + inchText
    }

    fun battery(ctx: Context): String {
        val intent: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0) level * 100 / scale else -1
        val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val volt = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
            BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "过压"
            else -> "未知"
        }
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000.0
        return "电量: " + pct + "%" +
                "\n温度: " + String.format(Locale.US, "%.1f", temp) + " °C" +
                "\n电压: " + volt + " mV" +
                "\n电流: " + String.format(Locale.US, "%.0f", current) + " mA" +
                "\n健康: " + health
    }

    fun sensors(ctx: Context): String {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val list = sm.getSensorList(Sensor.TYPE_ALL)
        val sb = StringBuilder("总数: " + list.size)
        for (s in list.take(20)) sb.append("\n• ").append(s.name)
        return sb.toString()
    }

    fun network(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val sb = StringBuilder()
        val active = cm.activeNetwork
        val caps = if (active != null) cm.getNetworkCapabilities(active) else null
        if (caps != null) {
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                else -> "其他"
            }
            sb.append("类型: ").append(type).append("\n")
            sb.append("下行带宽: ").append(caps.linkDownstreamBandwidthKbps / 1000).append(" Mbps\n")
            sb.append("上行带宽: ").append(caps.linkUpstreamBandwidthKbps / 1000).append(" Mbps\n")
        }
        sb.append("本机 IPv4: ").append(Net.localIpv4()?.first ?: "未知").append("\n")
        return sb.toString().trim()
    }

    fun uptime(): String {
        val ms = android.os.SystemClock.elapsedRealtime()
        val s = ms / 1000
        return "开机时长: " + (s / 3600) + " 时 " + ((s % 3600) / 60) + " 分 " + (s % 60) + " 秒" +
                "\n当前时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    }

    fun fmt(bytes: Long): String {
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }
}
