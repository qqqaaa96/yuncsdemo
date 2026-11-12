package sy.great.toolbox.tools

import android.content.Context

/**
 * 真实帧率读取（应用实际渲染帧数，不是屏幕刷新率）。
 *
 * 两者的区别（重要）：
 *   · 刷新率 —— 屏幕每秒刷多少次（measured_fps 节点），游戏掉帧时它仍是 120
 *   · 帧率   —— 应用每秒真的画了多少帧，游戏卡顿时它会降到 30 / 45
 *   用户要看“卡不卡”，必须看后者。
 *
 * 数据源（按可靠性排序）：
 *   1) dumpsys SurfaceFlinger --latency <layer>
 *      每个应用都有自己的 layer，返回 128 行纳秒时间戳。
 *      相邻时间戳的差 → 帧间隔 → 1/间隔 = 瞬时帧率。
 *      这是 Android 官方调试手段，不依赖厂商驱动，兼容性最好。
 *
 *   2) dumpsys SurfaceFlinger --fps（Android 12+）
 *      部分 ROM 支持，直接给出全局帧率。
 *
 * 两者都需 root / shell 身份：SurfaceFlinger 的 layer 信息普通应用读不到。
 *
 * 如实说明：
 *   · 需要知道当前前台应用的 layer 名，不同 ROM 命名不同
 *   · 部分 ROM 阉割了 --latency，此时返回 null（UI 显示 --）
 */
object FpsReader {

    /**
     * 一次读取结果。
     *
     * fps：真实帧率（两位小数）；null 表示读不到
     * layer：本次采样的 layer 名（用于诊断）
     */
    data class Reading(
        val fps: Float?,
        val layer: String = "",
        val error: String = "",
    )

    /** 上一条可用的 layer 名，避免每次都重新查找（查找开销大） */
    @Volatile
    private var cachedLayer: String = ""

    /**
     * 读一次真实帧率。
     *
     * 阻塞调用，必须在 IO 线程使用。高频采样时每次约耗时 5~20ms（一次 dumpsys）。
     */
    fun read(ctx: Context): Reading {
        if (!available(ctx)) {
            return Reading(null, error = "无提权环境")
        }

        // ---- 1. 已知 layer 直接读 ----
        cachedLayer.takeIf { it.isNotBlank() }?.let { layer ->
            parseLatency(run(ctx, "dumpsys SurfaceFlinger --latency '$layer'"), layer)
                ?.let { return Reading(it, layer) }
            // 失效（切应用 / layer 重建）→ 清空重找
            cachedLayer = ""
        }

        // ---- 2. 找当前应用的 layer ----
        val layer = findForegroundLayer(ctx)
        if (layer.isNotBlank()) {
            val fps = parseLatency(run(ctx, "dumpsys SurfaceFlinger --latency '$layer'"), layer)
            if (fps != null) {
                cachedLayer = layer
                return Reading(fps, layer)
            }
        }

        // ---- 3. 兑底：全局 --fps（Android 12+，部分 ROM 才有）----
        parseGlobalFps(run(ctx, "dumpsys SurfaceFlinger --fps"))
            ?.let { return Reading(it, "global") }

        return Reading(null, error = "本机不支持 --latency 采样")
    }

    /** 是否有提权通道 */
    fun available(ctx: Context): Boolean = hasAdb() || hasRoot()

    // ---------------- layer 查找 ----------------

    /**
     * 找当前前台应用的 SurfaceFlinger layer 名。
     *
     * 做法：从 dumpsys SurfaceFlinger 输出里找形如
     *   "SurfaceView[com.tencent.tmgp.sgame/...]@0"
     *   "com.miui.home/com.miui.home.launcher.Launcher#0"
     * 的行，优先含 SurfaceView 的（游戏全屏渲染层）。
     */
    private fun findForegroundLayer(ctx: Context): String {
        val out = run(ctx, "dumpsys SurfaceFlinger --list")
        if (out.isBlank()) return ""

        val lines = out.lines().map { it.trim() }.filter { it.isNotBlank() }

        // 1. 优先：SurfaceView 层（游戏几乎都用这个）
        lines.firstOrNull { it.contains("SurfaceView[") && it.contains("/") }
            ?.let { return it }

        // 2. 次选：普通 Activity 层（含包名 + 斜线 + #序号）
        lines.firstOrNull {
            it.contains("/") && !it.contains("Background") &&
                !it.startsWith("com.android.systemui") &&
                !it.contains("Splash")
        }?.let { return it }

        return ""
    }

    // ---------------- 解析 ----------------

    /**
     * 解析 dumpsys SurfaceFlinger --latency 的输出。
     *
     * 格式（Android 11+）：
     *   第 1 行：刷新周期（纳秒），例如 8333333（120Hz）
     *   后续 128 行：每帧的呈现时间戳（纳秒），0 或 9223372036854775807 表示无效
     *
     * 算法：
     *   取相邻两个有效时间戳的差 → 帧间隔（纳秒）
     *   统计间隔的中位数（比平均值稳，不受偶发跳帧干扰）
     *   fps = 1e9 / 中位间隔
     *
     * 为什么用中位数：
     *   偶尔卡一下会有个超大间隔，平均值会被拉低，中位数不受影响。
     */
    private fun parseLatency(raw: String, layer: String): Float? {
        if (raw.isBlank()) return null

        val invalid = 9223372036854775807L
        val stamps = ArrayList<Long>(128)

        for (line in raw.lines()) {
            val s = line.trim()
            if (s.isEmpty()) continue
            val v = s.toLongOrNull() ?: continue
            // 过滤无效值：0 和 Long.MAX_VALUE 都表示“这一帧没数据”
            if (v <= 0L || v >= invalid) continue
            stamps.add(v)
        }

        if (stamps.size < 3) return null

        // 相邻差
        val gaps = ArrayList<Long>(stamps.size - 1)
        for (i in 1 until stamps.size) {
            val d = stamps[i] - stamps[i - 1]
            // 合理帧间隔：对应 1~300fps，超出范围的按异常丢掉
            if (d in 3_333_333L..1_000_000_000L) gaps.add(d)
        }
        if (gaps.size < 2) return null

        gaps.sort()
        val median = gaps[gaps.size / 2]
        if (median <= 0L) return null

        val fps = 1_000_000_000f / median.toFloat()
        // 合理性限制：0.5~300fps
        return fps.takeIf { it.isFinite() && it in 0.5f..300f }
    }

    /**
     * 解析 dumpsys SurfaceFlinger --fps（Android 12+）。
     *
     * 输出形如：
     *   120.00 fps
     *   或带历史： 60.00 60.00 59.98 ...
     * 取最后一组数字。
     */
    private fun parseGlobalFps(raw: String): Float? {
        if (raw.isBlank()) return null
        val nums = Regex("([0-9]+\\.[0-9]+)").findAll(raw).mapNotNull { it.groupValues[1].toFloatOrNull() }.toList()
        val last = nums.lastOrNull() ?: return null
        return last.takeIf { it.isFinite() && it in 0.5f..300f }
    }

    // ---------------- 执行 ----------------

    private fun hasAdb(): Boolean = try {
        AdbShell.granted()
    } catch (_: Throwable) {
        false
    }

    private fun hasRoot(): Boolean = try {
        RootState.report.value?.granted == true
    } catch (_: Throwable) {
        false
    }

    private fun run(ctx: Context, cmd: String): String {
        if (hasAdb()) {
            try {
                val out = AdbShell.exec(ctx.applicationContext, cmd)?.out
                if (!out.isNullOrBlank()) return out
            } catch (_: Throwable) {
            }
        }
        if (hasRoot()) {
            try {
                val r = Shell.runRoot(cmd)
                val out = r.result?.out.orEmpty()
                if (out.isNotBlank()) return out
            } catch (_: Throwable) {
            }
        }
        return ""
    }

    // ---------------- 格式化 ----------------

    /** 格式化为 “119.88 FPS”；无效时 “-- FPS” */
    fun format(fps: Float?): String =
        if (fps == null || !fps.isFinite() || fps <= 0f) "-- FPS"
        else String.format(java.util.Locale.US, "%.2f FPS", fps)

    /** 诊断用：列出所有可用 layer */
    fun listLayers(ctx: Context): String {
        val out = run(ctx, "dumpsys SurfaceFlinger --list")
        if (out.isBlank()) return "读取失败（需 Root / Shizuku）"
        return out.lines().filter { it.isNotBlank() }.take(60).joinToString("\n")
    }
}
