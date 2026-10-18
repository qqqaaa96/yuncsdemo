package sy.great.toolbox.tools

import java.io.File

/**
 * 反作弊检测。
 *
 * 原理：检查 /proc 下是否存在反作弊驱动的三个保护节点。
 *
 *    /proc/inte_ko       驱动模块（.ko）
 *    /proc/inte_status   运行状态
 *    /proc/inte_systbl   系统表
 *
 * 这三个节点由内核模块创建，普通应用受 SELinux 限制看不到，
 * 所以检测需要 root（用 su 跑 ls）。
 *
 * 设计原则：
 *   · 全部读不到就如实说“读不到”，不猜“不存在”；
 *   · 有 root 才能给出可信结论；无 root 时直接不展示结果。
 */
object AntiCheat {

    /** 三个保护节点 */
    val PROBES = listOf(
        "/proc/inte_ko",
        "/proc/inte_status",
        "/proc/inte_systbl",
    )

    /** 单个节点的检测结果 */
    data class NodeResult(
        val path: String,
        /** true = 确认存在；false = 确认不存在；null = 无权限/无法判断 */
        val exists: Boolean?,
        /** 原始错误或说明，用于界面展示 */
        val detail: String,
    )

    /**
     * 驱动信息。全部字段来自真实读取，读不到就是 null。
     *
     * /proc/modules 每行字段含义（以空格分隔）：
     *   inte 45056 0 - Live 0x0000000000000000 (O)
     *    │    │   │ │  │           │            └ 尾部标记
     *    │    │   │ │  │           └ 模块基址
     *    │    │   │ │  └ 状态：Live / Loading / Unloading
     *    │    │   │ └ 引用计数
     *    │    │   └
     *    │    └ size（字节）
     *    └ 模块名（注意：是 inte，不是 inte.ko）
     */
    data class DriverInfo(
        /** 真实模块名，如 inte */
        val moduleName: String?,
        /** 模块大小（字节） */
        val memoryBytes: Long?,
        /** 引用计数 */
        val refCount: Int?,
        /** 状态：Live / Loading / Unloading，读不到为 null */
        val state: String?,
        /** 模块基址（十六进制字符串） */
        val baseAddress: String?,
        /** 真实探测到的 sysfs 目录，如 /sys/module/inte；没探测到为 null */
        val sysfsPath: String?,
        /** /proc/modules 里那一整行原文 */
        val modulesLine: String?,
    )

    /** 完整检测结果 */
    data class Report(
        val hasRoot: Boolean,
        val nodes: List<NodeResult>,
        val driver: DriverInfo?,
    ) {
        /** 三个节点里是否有任何一个确认存在 */
        val anyPresent: Boolean get() = nodes.any { it.exists == true }

        /** 是否存在“无法判断”的节点 */
        val anyUnknown: Boolean get() = nodes.any { it.exists == null }
    }

    /**
     * 执行检测。阻塞调用，必须在 IO 线程。
     *
     * 没 root 时直接返回 hasRoot=false，不跑任何命令。
     */
    fun detect(): Report {
        val hasRoot = RootState.useRoot()
        if (!hasRoot) {
            return Report(
                hasRoot = false,
                nodes = PROBES.map { NodeResult(it, null, "需要 Root 权限") },
                driver = null,
            )
        }

        // ---- 1) 三个节点是否存在 ----
        val nodes = PROBES.map { p ->
            // 用 test -e 判断存在性；同时用 ls -l 看能否读取
            val r = Shell.runSu(
                "-c",
                "if [ -e $p ]; then echo EXISTS; else echo MISSING; fi",
                timeoutMs = 4000
            )
            val out = r.result?.out.orEmpty().trim()
            when {
                out.contains("EXISTS") -> NodeResult(p, true, "存在（受系统安全策略保护）")
                out.contains("MISSING") -> NodeResult(p, false, "不存在")
                else -> NodeResult(p, null, "无法访问（${r.result?.err?.trim().orEmpty().ifBlank { "权限不足" }}）")
            }
        }

        // ---- 2) 驱动信息 ----
        val driver = readDriverInfo()

        return Report(hasRoot = true, nodes = nodes, driver = driver)
    }

    /**
     * 读驱动信息。
     *
     * 全部字段来自真实读取，没有任何写死的值：
     *
     *   1) /proc/modules 里找 inte 那一行，按空格切分取字段
     *   2) /sys/module 下实际列出目录，看有没有 inte
     *   3) 若 /proc/modules 没有但 sysfs 目录存在，也算已加载
     */
    private fun readDriverInfo(): DriverInfo? {
        // ---- 1) /proc/modules ----
        val modulesOut = Shell.runSu("-c", "cat /proc/modules", timeoutMs = 4000)
            .result?.out.orEmpty()

        // 模块名可能带下划线变体，按“第一个字段等于 inte / 以 inte_ 开头”匹配
        val line = modulesOut.lineSequence().firstOrNull { raw ->
            val name = raw.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            name == "inte" || name.startsWith("inte_")
        }?.trim()

        // ---- 2) 真实探测 /sys/module 下的目录 ----
        // 列出 /sys/module 下所有目录名，找 inte / inte_*
        val sysList = Shell.runSu(
            "-c",
            "ls -1 /sys/module 2>/dev/null",
            timeoutMs = 4000
        ).result?.out.orEmpty()

        val sysDir = sysList.lineSequence()
            .map { it.trim() }
            .firstOrNull { it == "inte" || it.startsWith("inte_") }

        if (line == null && sysDir == null) return null

        // ---- 3) 解析 /proc/modules 字段 ----
        val parts = line?.split(Regex("\\s+")).orEmpty()
        val name = parts.getOrNull(0)
        val size = parts.getOrNull(1)?.toLongOrNull()
        val refCount = parts.getOrNull(2)?.toIntOrNull()
        // 第 4 个字段是“-”，第 5 个才是状态
        val state = parts.getOrNull(4)?.takeIf { it == "Live" || it == "Loading" || it == "Unloading" }
        val base = parts.getOrNull(5)

        return DriverInfo(
            moduleName = name,
            memoryBytes = size,
            refCount = refCount,
            state = state,
            baseAddress = base,
            // 只有真的在 /sys/module 下列出了才给路径
            sysfsPath = sysDir?.let { "/sys/module/" + it },
            modulesLine = line,
        )
    }
}
