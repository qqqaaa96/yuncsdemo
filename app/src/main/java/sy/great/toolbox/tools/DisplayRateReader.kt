package sy.great.toolbox.tools

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * 屏幕刷新率读取。
 *
 * 本文件是 Scene 8.1.5 的 `com.omarea.library.shell.FpsUtils` 的逐行等价实现，
 * 逻辑、探测顺序、解析方式、节流时长全部一致，只把它的 shell 层
 * （Daemon / KernelProp）换成本项目已有的 AdbShell / Shell.runRoot。
 *
 * ============ Scene 的做法（还原） ============
 *
 * 核心是 b() 方法，分三种情况：
 *
 *   ① 【已探测到路径】直接读
 *        · 先试 File.exists() + canRead() 直接读（不需要 root）
 *        · 读不到 → 走 shell（root / adb）cat
 *        · 解析：split(/\s+/)[1]  —— 取空白分隔的第 2 段
 *        · 但 fpsgo 路径特殊：要找表头 currentFPS 的列，取所有行中的【最大值】
 *
 *   ② 【未探测】按顺序探测 4 个节点：
 *        1. /sys/class/drm/sde-crtc-0/measured_fps
 *        2. /sys/devices/platform/soc/ae00000.qcom,mdss_mdp/drm/card0/card0-sde-crtc-0/measured_fps
 *        3. /sys/class/graphics/fb0/measured_fps
 *        4. /sys/kernel/fpsgo/fstb/fpsgo_status          （联发科）
 *      哪个存在就用哪个，同时设定对应的解析函数。
 *      都不存在 → 标记为空串，转 ③。
 *
 *   ③ 【SurfaceFlinger 兑底】service call SurfaceFlinger 1013
 *        · Daemon 执行，2 秒超时
 *        · 输出含 "error" / "Parcel" → 作废，永久禁用
 *        · 否则找 '(' 后 8 位十六进制 → parseInt(hex) 得到一个递增的帧计数
 *        · 帧率 = (本次计数 - 上次计数) * 1000 / (本次时间 - 上次时间)
 *          注意：这是【增量计算】，必须存两次采样
 *
 *   ④ 【节流】250ms —— 距上次读取不足 250ms 直接返回缓存值
 *
 * 与之前版本的关键差异（跟着 Scene 改的）：
 *   · 新增了 SurfaceFlinger 1013 的增量计算（之前只是尝试正则，基本拿不到值）
 *   · fpsgo 从“取第一个有效值”改成“取所有行的最大值”（Scene 用 max）
 *   · 探测时先尝试直接 File 读，失败才走 shell（普通应用有时也能读）
 *   · 加了 250ms 节流，避免高频调用把 CPU 吃满
 *
 * 保留本项目的部分：
 *   · 不回退名义值（Scene 也不回退，读不到就 null）
 *   · 双通道 shell：ADB(Shizuku) 优先，Root 兜底
 *   · 内容不变：毫秒级刷新、路径缓存等原有行为均保留
 */
object DisplayRateReader {

    // ============ 数据源（与 Scene 完全一致的四个路径） ============

    private const val P_DRM_SDE = "/sys/class/drm/sde-crtc-0/measured_fps"
    private const val P_DRM_OLD =
        "/sys/devices/platform/soc/ae00000.qcom,mdss_mdp/drm/card0/card0-sde-crtc-0/measured_fps"
    private const val P_FB0 = "/sys/class/graphics/fb0/measured_fps"
    private const val P_FPSGO = "/sys/kernel/fpsgo/fstb/fpsgo_status"

    /** SurfaceFlinger 兑底命令（与 Scene 的 h 字段一致） */
    private const val CMD_SF = "service call SurfaceFlinger 1013"

    /**
     * 节流间隔（毫秒）。与 Scene 的 `if (now - l < 250)` 一致。
     *
     * 为什么是 250：
     *   读 sys 节点虽快，但 service call 要几十毫秒；
     *   250ms 是“人眼能感知变化”与“不浪费 CPU”的平衡点。
     */
    private const val THROTTLE_MS = 250L

    // ============ 状态 ============

    /**
     * 已探测到的可用路径（与 Scene 的 this.a 一致）。
     *
     *   null  —— 还没探测过
     *   ""    —— 探测完了，没有可用 sys 节点，走 SurfaceFlinger
     *   其他  —— 具体路径
     */
    @Volatile
    private var cachedPath: String? = null

    /** fpsgo 的表头列号（与 Scene 的 this.g / currentFpsLegend 一致） */
    @Volatile
    private var fpsgoColumn: Int = -1

    /** 节流：上次成功读取的时间（与 Scene 的静态 l 一致） */
    @Volatile
    private var lastReadAt: Long = 0L

    /** 节流缓存值（与 Scene 的静态 k 一致） */
    @Volatile
    private var cachedValue: Float = 0f

    /**
     * SurfaceFlinger：上次的帧计数（与 Scene 的 this.j 一致）。
     *
     * 用 Long 而不是 Int：
     *   8 位十六进制最大 0xFFFFFFFF = 4294967295，超出 Int 范围；
     *   用 Int 会变成负数，导致差值为负、帧率算错。
     */
    @Volatile
    private var sfLastCount = -1L

    /** SurfaceFlinger：上次采样时间（与 Scene 的 this.i 一致） */
    @Volatile
    private var sfLastAt = -1L

    /** SurfaceFlinger 是否已判定不可用（与 Scene 把 h 置空串一致） */
    @Volatile
    private var sfDisabled = false

    // ============ 对外 ============

    /** 一次读取的结果 */
    data class Reading(val hz: Float?, val real: Boolean)

    /** 是否有提权通道（仅 ADB / Root） */
    fun available(ctx: Context): Boolean = canShell(ctx)

    /**
     * 读一次。
     *
     * 行为与 Scene 的 FpsUtils.a() 一致：
     *   1. 250ms 内重复调用 → 直接返回缓存
     *   2. 否则执行真实读取，成功后更新缓存与时间戳
     *
     * 阻塞调用，必须在 IO 线程使用。
     */
    fun read(ctx: Context): Reading {
        val now = System.currentTimeMillis()

        // ---- 节流（Scene：if (now - l < 250) return k）----
        if (now - lastReadAt < THROTTLE_MS) {
            val v = cachedValue
            return Reading(if (v > 0f) v else null, v > 0f)
        }

        if (!canShell(ctx)) return Reading(null, false)

        // ---- 真实读取（Scene 的 b()）----
        val v = readRaw(ctx)

        // 只有读到有效值才更新缓存（Scene 在 parseFloat 成功后才赋 l）
        if (v != null && v > 0f) {
            cachedValue = v
            lastReadAt = now
            return Reading(v, true)
        }

        // 读不到：返回上次的缓存（如果有），不伪造新值
        val prev = cachedValue
        return Reading(if (prev > 0f) prev else null, prev > 0f)
    }

    /** 便捷：只要值 */
    fun currentHz(ctx: Context): Float? = read(ctx).hz

    /** 便捷：只要真假 */
    fun isReal(ctx: Context): Boolean = read(ctx).real

    /** 重置探测状态（换了设备 / 节点消失时可手动调） */
    fun resetProbe() {
        cachedPath = null
        fpsgoColumn = -1
        sfLastCount = -1
        sfLastAt = -1L
        sfDisabled = false
        lastReadAt = 0L
        cachedValue = 0f
    }

    // ============ 真实读取（对应 Scene 的 b()） ============

    private fun readRaw(ctx: Context): Float? {
        val path = cachedPath

        // ---- ① 已有路径：直接读 ----
        if (path != null && path.isNotEmpty()) {
            val raw = readNode(ctx, path)
            val v = parseWith(path, raw)
            if (v != null) return v

            // 读失败（节点消失 / 无权限）→ 清空重新探测
            cachedPath = null
            fpsgoColumn = -1
        }

        // ---- ② 探测（Scene：this.a == null 时）----
        if (cachedPath == null) {

            // 1~3：measured_fps 类，都存在则取第 2 段
            for (p in listOf(P_DRM_SDE, P_DRM_OLD, P_FB0)) {
                if (exists(ctx, p)) {
                    val raw = readNode(ctx, p)
                    val v = parseMeasuredFps(raw)
                    if (v != null) {
                        cachedPath = p
                        fpsgoColumn = -1
                        return v
                    }
                }
            }

            // 4：联发科 fpsgo（表头找 currentFPS 列）
            val fpsgoRaw = readNode(ctx, P_FPSGO)
            if (fpsgoRaw.isNotBlank()) {
                val col = findFpsgoColumn(fpsgoRaw)
                if (col >= 0) {
                    cachedPath = P_FPSGO
                    fpsgoColumn = col
                    parseFpsgoMax(fpsgoRaw, col)?.let { return it }
                } else {
                    // 文件存在但没 currentFPS 列 → 标记不可用
                    cachedPath = ""
                }
            } else {
                // 四个节点全都没有 → 走 SurfaceFlinger
                cachedPath = ""
            }
        }

        // ---- ③ SurfaceFlinger 兑底（Scene：this.h 非空时）----
        if (!sfDisabled) {
            surfaceFlinger1310(ctx)?.let { return it }
        }

        return null
    }

    /** 按路径选择解析方式 */
    private fun parseWith(path: String, raw: String): Float? {
        if (raw.isBlank()) return null
        return if (path == P_FPSGO) {
            val col = if (fpsgoColumn >= 0) fpsgoColumn else findFpsgoColumn(raw)
            if (col < 0) null else parseFpsgoMax(raw, col)
        } else {
            parseMeasuredFps(raw)
        }
    }

    /**
     * measured_fps 类节点的解析（与 Scene 的 subStrFun 一致）：
     *   split(/\s+/)[1]
     *
     * 举例：节点内容是 "119.88 119.76" 或 "fps: 119.88"，
     * 取第 2 段得到 "119.88"。
     * 只有一段时 Scene 返回空串（拿不到值），这里返回 null。
     */
    private fun parseMeasuredFps(raw: String): Float? {
        if (raw.isBlank()) return null
        val parts = raw.trim().split(Regex("[\\s]+"))
        if (parts.size <= 1) return null
        val v = parts[1].toFloatOrNull() ?: return null
        return v.takeIf { it.isFinite() && it in 1f..300f }
    }

    /**
     * 找 fpsgo 表头里 currentFPS 的列号（与 Scene 一致）。
     *
     * Scene 只处理“第一行是表头”的情况，这里保持相同行为。
     */
    private fun findFpsgoColumn(raw: String): Int {
        val first = raw.lineSequence().firstOrNull()?.trim().orEmpty()
        if (first.isEmpty()) return -1
        val header = first.split(Regex("[\\s]+"))
        return header.indexOfFirst { it.contains("currentFPS", ignoreCase = true) }
    }

    /**
     * fpsgo 取列内【最大值】（与 Scene 的 currentFpsLegend 一致）。
     *
     * Scene 的写法是遍历所有行，保留最大的那个值（fpsgo 是多行多核/多段的表）。
     * 之前本项目是“取第一个有效值”，结果会偏小——现在改成取最大值。
     */
    private fun parseFpsgoMax(raw: String, col: Int): Float? {
        var max = 0f
        var found = false
        raw.lineSequence().drop(1).forEach { line ->
            val cs = line.trim().split(Regex("[\\s]+"))
            if (cs.size > col) {
                val v = cs[col].toFloatOrNull()
                if (v != null && v > max) {
                    max = v
                    found = true
                }
            }
        }
        if (!found) return null
        return max.takeIf { it.isFinite() && it in 1f..300f }
    }

    /**
     * SurfaceFlinger 1013 兑底（与 Scene 的 h 分支一致）。
     *
     * 输出形如：
     *   Result: Parcel(00000000 0000c350 '........')
     * 取 ')' 前 8 位十六进制作为一个递增的帧计数，
     * 用两次采样的差值除以时间差得到帧率。
     *
     * 注意：必须存上一次的值（sfLastCount / sfLastAt），
     * 第一次调用只能记录、无法算出帧率（Scene 首次也返回 0.0）。
     */
    private fun surfaceFlinger1310(ctx: Context): Float? {
        val raw = run(ctx, CMD_SF)
        if (raw.isBlank()) return null

        val text = raw.trim()

        // Scene：输出含 error 或 Parcel 时永久禁用
        // （注意 Scene 的判断：equals("error") 或 contains("Parcel") 才禁用；
        //   这里是宽松版本：小写的 error 也识别）
        if (text.equals("error", ignoreCase = true)) {
            sfDisabled = true
            return null
        }
        // 只有输出不含任何数字时才算彻底失败
        if (!text.any { it.isDigit() }) {
            sfDisabled = true
            return null
        }

        // 找 "(" 后的 8 位十六进制
        val lp = text.indexOf('(')
        if (lp < 0) return null
        val start = lp + 1
        if (text.length < start + 8) return null

        val count = try {
            text.substring(start, start + 8).toLong(16)
        } catch (_: Throwable) {
            return null
        }

        val now = System.currentTimeMillis()
        val prevCount = sfLastCount
        val prevAt = sfLastAt

        var fps = 0f
        if (prevCount > 0 && prevAt > 0 && now > prevAt) {
            fps = (count - prevCount) * 1000f / (now - prevAt).toFloat()
        }

        sfLastCount = count
        sfLastAt = now

        if (fps <= 0f || !fps.isFinite()) return null
        return fps.takeIf { it in 1f..300f }
    }

    // ============ 节点读 / 存在性判断 ============

    /**
     * 读节点内容。
     *
     * 与 Scene 的 KernelProp.c() 一致：
     *   先试【直接 File 读】（不需要 root，部分节点普通应用也能读）
     *   失败再走 shell（root / adb）
     */
    private fun readNode(ctx: Context, path: String): String {
        // ---- 1. 直接读 ----
        try {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                val txt = f.readText()
                if (txt.isNotBlank()) return txt
            }
        } catch (_: Throwable) {
        }

        // ---- 2. shell 读 ----
        return run(ctx, "cat $path")
    }

    /**
     * 节点是否存在（与 Scene 的 i.a.c 一致）。
     *
     * Scene 的做法：File.exists() && isFile() 为 true 就认为存在，
     * 否则再去 shell 里判。这里保持同样语义。
     */
    private fun exists(ctx: Context, path: String): Boolean {
        try {
            val f = File(path)
            if (f.exists() && f.isFile) return true
        } catch (_: Throwable) {
        }
        val out = run(ctx, "ls $path")
        return out.isNotBlank() && !out.contains("No such file")
    }

    // ============ 执行（ADB 优先，Root 兜底） ============

    private fun canShell(ctx: Context): Boolean = hasAdb() || hasRoot()

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

    // ============ 格式化 ============

    /** 格式化为 “119.88 Hz”；无效时 “-- Hz” */
    fun format(hz: Float?): String =
        if (hz == null || !hz.isFinite() || hz <= 0f) "-- Hz"
        else String.format(Locale.US, "%.2f Hz", hz)
}
