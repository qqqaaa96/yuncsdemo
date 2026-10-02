package com.csdemo.tools

import java.io.File

/**
 * Root 调优：调度、温控墙、线程优化、CPU 频率。
 * 所有写入都通过 su 完成，读不到就返回空列表，不编造数据。
 */
object RootTune {

    private const val CPU = "/sys/devices/system/cpu"

    // ---------- CPU 核心 ----------

    fun cores(): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i < 64) {
            if (File(CPU + "/cpu" + i).exists()) out.add(i) else if (i > 0) break
            i++
        }
        if (out.isEmpty()) out.add(0)
        return out
    }

    // ---------- CPU 频率 ----------

    data class FreqInfo(
        val core: Int,
        val available: List<Long>,   // kHz
        val currentMin: Long,
        val currentMax: Long,
        val current: Long,
        val governor: String
    )

    private fun readSys(path: String): String =
        try {
            val f = File(path)
            if (f.exists() && f.canRead()) f.readText().trim() else ""
        } catch (e: Exception) {
            ""
        }

    private fun readRoot(path: String): String {
        val r = Shell.runRoot("cat " + path)
        return r.result?.out?.trim().orEmpty()
    }

    fun freqOf(core: Int): FreqInfo {
        val base = CPU + "/cpu" + core + "/cpufreq"
        val avail = readSys(base + "/scaling_available_frequencies")
            .split(" ", ",")
            .mapNotNull { it.trim().toLongOrNull() }
            .sorted()
        var curMin = readSys(base + "/scaling_min_freq").toLongOrNull() ?: 0L
        var curMax = readSys(base + "/scaling_max_freq").toLongOrNull() ?: 0L
        var cur = readSys(base + "/scaling_cur_freq").toLongOrNull() ?: 0L
        var gov = readSys(base + "/scaling_governor")
        if (curMax == 0L || gov.isBlank()) {
            if (curMin == 0L) curMin = readRoot(base + "/scaling_min_freq").toLongOrNull() ?: 0L
            if (curMax == 0L) curMax = readRoot(base + "/scaling_max_freq").toLongOrNull() ?: 0L
            if (cur == 0L) cur = readRoot(base + "/scaling_cur_freq").toLongOrNull() ?: 0L
            if (gov.isBlank()) gov = readRoot(base + "/scaling_governor")
        }
        return FreqInfo(core, avail, curMin, curMax, cur, gov)
    }

    fun setFreq(core: Int, minKhz: Long?, maxKhz: Long?): Boolean {
        val base = CPU + "/cpu" + core + "/cpufreq"
        val cmds = ArrayList<String>()
        if (maxKhz != null) cmds.add("echo " + maxKhz + " > " + base + "/scaling_max_freq")
        if (minKhz != null) cmds.add("echo " + minKhz + " > " + base + "/scaling_min_freq")
        if (cmds.isEmpty()) return false
        val r = Shell.runRoot(cmds.joinToString(" && "))
        return r.succeeded()
    }

    // ---------- 调度 ----------

    fun availableGovernors(core: Int): List<String> =
        readSys(CPU + "/cpu" + core + "/cpufreq/scaling_available_governors")
            .split(" ").map { it.trim() }.filter { it.isNotBlank() }

    fun setGovernor(core: Int, gov: String): Boolean {
        val path = CPU + "/cpu" + core + "/cpufreq/scaling_governor"
        return Shell.runRoot("echo " + gov + " > " + path).succeeded()
    }

    fun ioGovernor(block: String = "sda"): String {
        var v = readSys("/sys/block/" + block + "/queue/scheduler")
        if (v.isBlank()) v = readRoot("/sys/block/" + block + "/queue/scheduler")
        return v
    }

    fun ioAvailable(): List<String> {
        val v = ioGovernor()
        val m = Regex("\\[(.+?)\\]").find(v)?.groupValues?.get(1)
        val all = v.replace("[", "").replace("]", "").split(" ").map { it.trim() }.filter { it.isNotBlank() }
        return all
    }

    fun ioCurrent(): String =
        Regex("\\[(.+?)\\]").find(ioGovernor())?.groupValues?.get(1).orEmpty()

    fun setIoGovernor(block: String, gov: String): Boolean =
        Shell.runRoot("echo " + gov + " > /sys/block/" + block + "/queue/scheduler").succeeded()

    // ---------- 温控墙 ----------

    data class ThermalZone(val id: Int, val type: String, val temp: Double)

    fun thermalZones(): List<ThermalZone> {
        val out = ArrayList<ThermalZone>()
        val base = "/sys/class/thermal"
        val dir = File(base)
        val entries = if (dir.exists() && dir.canRead()) dir.list() else null
        if (entries != null) {
            for (e in entries) {
                if (!e.startsWith("thermal_zone")) continue
                val id = e.removePrefix("thermal_zone").toIntOrNull() ?: continue
                val type = readSys(base + "/" + e + "/type")
                val t = readSys(base + "/" + e + "/temp").toLongOrNull()
                if (t != null) out.add(ThermalZone(id, type.ifBlank { e }, t / 1000.0))
            }
        }
        if (out.isEmpty()) {
            for (i in 0 until 30) {
                val t = readRoot(base + "/thermal_zone" + i + "/temp").toLongOrNull() ?: continue
                val ty = readRoot(base + "/thermal_zone" + i + "/type")
                out.add(ThermalZone(i, ty.ifBlank { "thermal_zone$i" }, t / 1000.0))
            }
        }
        return out.sortedBy { it.id }
    }

    /** 当前生效的温控触发点（可用时） */
    fun thermalTrip(zoneId: Int): String {
        val base = "/sys/class/thermal/thermal_zone" + zoneId
        val p = base + "/trip_point_0_temp"
        var v = readSys(p)
        if (v.isBlank()) v = readRoot(p)
        return v
    }

    fun setThermalTrip(zoneId: Int, value: Long): Boolean =
        Shell.runRoot("echo " + value + " > /sys/class/thermal/thermal_zone" + zoneId + "/trip_point_0_temp").succeeded()

    /** 温控模式（部分内核有） */
    fun thermalModePaths(): List<String> {
        val list = ArrayList<String>()
        for (p in listOf(
            "/sys/class/thermal/thermal_message",
            "/sys/module/msm_thermal/parameters/enabled",
            "/sys/module/thermal/parameters/mode",
            "/proc/sys/kernel/thermal_limit"
        )) {
            if (File(p).exists()) list.add(p)
        }
        return list
    }

    fun readPath(path: String): String {
        var v = readSys(path)
        if (v.isBlank()) v = readRoot(path)
        return v
    }

    fun writePath(path: String, value: String): Boolean =
        Shell.runRoot("echo " + value + " > " + path).succeeded()

    // ---------- 线程优化 ----------

    /** 把本进程/指定进程调到高优先级，并可选调整内存与 vm 参数 */
    fun optimizeProcess(pkg: String, priority: Int): String {
        val cmds = ArrayList<String>()
        cmds.add("PGID=\$(pidof " + pkg + ")")
        cmds.add("if [ -z \"\$PGID\" ]; then echo '进程未运行'; exit 0; fi")
        cmds.add("renice " + priority + " -p \$PGID")
        cmds.add("echo 'renice ok:' \$PGID")
        val r = Shell.runRoot(*cmds.toTypedArray())
        return if (r.invoked) (r.result?.out?.trim().orEmpty().ifBlank { "已提交" }) else "失败：未获取 Root"
    }

    /** 通用 vm 参数读取 */
    fun vmValue(name: String): String {
        var v = readSys("/proc/sys/vm/" + name)
        if (v.isBlank()) v = readRoot("/proc/sys/vm/" + name)
        return v
    }

    fun setVmValue(name: String, value: String): Boolean =
        Shell.runRoot("echo " + value + " > /proc/sys/vm/" + name).succeeded()

    /** 一键后台优化：限制后台进程、清缓存 */
    fun backgroundTune(): String {
        val cmds = arrayOf(
            "sync",
            "echo 3 > /proc/sys/vm/drop_caches",
            "echo 'done'"
        )
        val r = Shell.runRoot(*cmds)
        return if (r.invoked) (r.result?.out?.trim().orEmpty().ifBlank { "已完成" }) else "失败：未获取 Root"
    }
}
