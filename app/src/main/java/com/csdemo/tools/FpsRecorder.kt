package com.csdemo.tools

import android.app.ActivityManager
import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 帧率录制器（参考 Scene 的帧率录制）。
 *
 * 采集项（照 Scene 的图表）：
 *   · 刷新率 / FPS —— 驱动实测节点（DisplayRateReader）
 *   · 温度         —— thermal_zone 节点
 *   · CPU 频率     —— cpufreq/scaling_cur_freq
 *   · CPU 使用率   —— proc/stat 差值
 *   · 内存已用     —— ActivityManager
 *   · GPU 频率     —— 高通 kgsl 节点（很多机型没有，读不到就是 null）
 *   · 帧时间       —— 1000 / fps 毫秒（由 fps 推算，非独立采集）
 *
 * 注意：注释里不要出现斜杠+星号的组合（如“路径斜杠星号”），
 * Kotlin 的块注释可嵌套，会把它当成新的注释开始而导致注释不闭合。
 *
 * 设计要点：
 *   · 单线程定时采集，不堆积任务；
 *   · 采不到就是 null，绝不用上一条值填充（否则波形会平滑失真）；
 *   · 采样快照存在内存列表，录制结束后供波形图读取；
 *   · 不持有 Context，只在使用时传入 applicationContext。
 *
 * 权限：走 Shizuku（ADB），与刷新率读取同一通道。
 */
object FpsRecorder {

    /** 一条采样 */
    data class Sample(
        /** 相对录制开始的毫秒数 */
        val t: Long,
        val fps: Float?,
        val tempC: Float?,
        val cpuFreqMhz: Long?,
        val cpuUsage: Float?,
        val memUsedG: Float?,
        val gpuMhz: Long?,
    )

    /** 采样间隔：50ms（20 次/秒），录几分钟也只有几万个点 */
    private const val INTERVAL_MS = 50L

    /** 最短录制时长（毫秒）：不足则不允许停止 */
    const val MIN_DURATION_MS = 10_000L

    /** 是否正在录制 */
    @Volatile
    var recording: Boolean = false
        private set

    /** 录制开始时刻（SystemClock 参考） */
    @Volatile
    private var startedAt: Long = 0L

    /** 采样数据 */
    val samples: MutableList<Sample> = CopyOnWriteArrayList()

    private var future: ScheduledFuture<*>? = null
    private val pool = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "fps-recorder").apply { isDaemon = true }
    }

    // CPU 使用率需要前后两次 /proc/stat 差值
    private var lastCpuTotal: Long = 0L
    private var lastCpuIdle: Long = 0L

    // ---------- 控制 ----------

    /** 已录制时长（毫秒）。未在录制时返回 0。 */
    fun elapsedMs(): Long = if (recording) System.currentTimeMillis() - startedAt else 0L

    /** 当前是否已达到可停止的最短时长 */
    fun canStop(): Boolean = !recording || elapsedMs() >= MIN_DURATION_MS

    /**
     * 开始录制。已在录制中则直接返回 true。
     */
    fun start(ctx: Context): Boolean {
        if (recording) return true
        val app = ctx.applicationContext

        samples.clear()
        lastCpuTotal = 0L
        lastCpuIdle = 0L
        startedAt = System.currentTimeMillis()
        recording = true

        return try {
            future = pool.scheduleWithFixedDelay(
                { safeSample(app) }, 0L, INTERVAL_MS, TimeUnit.MILLISECONDS
            )
            true
        } catch (_: Throwable) {
            recording = false
            false
        }
    }

    /**
     * 停止录制并返回快照。未在录制中则返回空列表。
     *
     * 注意：不在这里判断最短时长，由调用方（悬浮窗）负责提示。
     */
    fun stop(): List<Sample> {
        recording = false
        try {
            future?.cancel(false)
        } catch (_: Throwable) {
        }
        future = null
        return samples.toList()
    }

    /** 丢弃数据（不清除录制状态） */
    fun clear() {
        samples.clear()
    }

    // ---------- 采样 ----------

    /**
     * 一次采样。
     *
     * 关键（性能）：不能在这 50ms 里分次调用多个 Shell。
     * 每次 shell 调用都是一次跨进程往返，
     * 这里把它合并成“一条命令输出多行”，一次往返拿全部。
     *
     * 输出约定（固定行序，行内用空格分隔）：
     *   1) 温区：type:temp   （多行）
     *   2) cpufreq0: <kHz>
     *   3) /proc/stat 的 cpu 行
     *   4) gpu: <Hz>   （可能为空）
     */
    private fun safeSample(ctx: Context) {
        try {
            val t = System.currentTimeMillis() - startedAt

            // 刷新率仍单独走自己的缓存路径（已做路径缓存，开销小）
            val fps = DisplayRateReader.read(ctx).hz

            // 其余指标合并成一条命令
            val raw = runShell(ctx, BATCH_CMD)
            val temp = parseTemp(raw)
            val freq = parseCpuFreq(raw)
            val usage = parseCpuUsage(raw)
            val gpu = parseGpu(raw)

            // 内存无需提权
            val mem = readMemUsedG(ctx)

            samples.add(Sample(t, fps, temp, freq, usage, mem, gpu))
        } catch (_: Throwable) {
            // 单次失败不中断整个录制
        }
    }

    /** 合并采集命令：一次 shell 往返拿回多项 */
    private val BATCH_CMD: String = buildString {
        // 温区
        append("for z in /sys/class/thermal/thermal_zone*; do ")
        append("[ -r \"\$z/temp\" ] && echo \"T:\$(cat \"\$z/type\ 2>/dev/null):\$(cat \"\$z/temp\" 2>/dev/null)\"; ")
        append("done; ")
        // CPU0 频率
        append("echo \"F:\$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq 2>/dev/null)\"; ")
        // /proc/stat
        append("echo \"S:\$(head -1 /proc/stat)\"; ")
        // GPU（可能都没有）
        append("for g in /sys/class/kgsl/kgsl-3d0/gpuclk /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/devfreq/gpufreq/cur_freq; do ")
        append("[ -r \"\$g\" ] && echo \"G:\$(cat \"\$g\")\" && break; ")
        append("done")
    }

    /** 执行（走 Shizuku；本功能不使用 su） */
    private fun runShell(ctx: Context, cmd: String): String = try {
        if (AdbShell.granted()) {
            AdbShell.exec(ctx.applicationContext, cmd)?.out.orEmpty()
        } else ""
    } catch (_: Throwable) {
        ""
    }

    /** 从合并输出里取温度：优先 cpu/soc/tsens 类温区 */
    private fun parseTemp(raw: String): Float? {
        if (raw.isBlank()) return null
        val zones = raw.lineSequence()
            .filter { it.startsWith("T:") }
            .mapNotNull { line ->
                val body = line.removePrefix("T:")
                val i = body.lastIndexOf(':')
                if (i <= 0) return@mapNotNull null
                val type = body.substring(0, i)
                val v = body.substring(i + 1).trim().toFloatOrNull() ?: return@mapNotNull null
                // 内核一般以毫摄氏度给出
                val c = if (v > 1000f) v / 1000f else v
                if (c <= 0f) null else type to c
            }
            .toList()

        if (zones.isEmpty()) return null
        val cpuLike = zones.firstOrNull {
            val s = it.first.lowercase()
            s.contains("cpu") || s.contains("soc") || s.contains("tsens") || s.contains("ap")
        }
        return (cpuLike ?: zones.first()).second
    }

    /** CPU0 频率（MHz） */
    private fun parseCpuFreq(raw: String): Long? {
        val line = raw.lineSequence().firstOrNull { it.startsWith("F:") } ?: return null
        val khz = line.removePrefix("F:").trim().toLongOrNull() ?: return null
        return if (khz > 0L) khz / 1000L else null
    }

    /** CPU 使用率：/proc/stat 差值 */
    private fun parseCpuUsage(raw: String): Float? {
        val line = raw.lineSequence().firstOrNull { it.startsWith("S:") } ?: return null
        val body = line.removePrefix("S:").trim()
        val nums = body.split(Regex("\\s+"))
            .drop(1)
            .mapNotNull { it.toLongOrNull() }
        if (nums.size < 5) return null
        val idle = nums[3] + (nums.getOrNull(4) ?: 0L)
        val total = nums.sum()
        if (lastCpuTotal == 0L) {
            lastCpuTotal = total
            lastCpuIdle = idle
            return null
        }
        val dTotal = total - lastCpuTotal
        val dIdle = idle - lastCpuIdle
        lastCpuTotal = total
        lastCpuIdle = idle
        if (dTotal <= 0L) return null
        return ((dTotal - dIdle).toFloat() / dTotal.toFloat() * 100f).coerceIn(0f, 100f)
    }

    /** GPU 频率（MHz） */
    private fun parseGpu(raw: String): Long? {
        val line = raw.lineSequence().firstOrNull { it.startsWith("G:") } ?: return null
        val hz = line.removePrefix("G:").trim().toLongOrNull() ?: return null
        return if (hz > 0L) hz / 1_000_000L else null
    }

    /** 系统已用内存（GB） */
    private fun readMemUsedG(ctx: Context): Float? = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        ((mi.totalMem - mi.availMem) / 1024.0 / 1024.0 / 1024.0).toFloat()
    } catch (_: Throwable) {
        null
    }

}
