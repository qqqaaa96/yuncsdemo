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

    /** 驱动信息（从 /proc/modules 或 /sys 读） */
    data class DriverInfo(
        val moduleName: String?,     // 如 inte.ko
        val memoryBytes: Long?,      // 如 45056
        val sysfsPath: String?,      // 如 /sys/module/inte
        val sysfsRegistered: Boolean,
        val modulesLine: String?,    // /proc/modules 里那一行
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
     * 数据源：
     *   · /proc/modules 里形如：inte 45056 0 - Live 0x0000000000000000 (O)
     *   · /sys/module/inte 目录是否存在
     */
    private fun readDriverInfo(): DriverInfo? {
        // /proc/modules 里找 inte
        val modulesOut = Shell.runSu("-c", "cat /proc/modules", timeoutMs = 4000)
            .result?.out.orEmpty()

        val line = modulesOut.lineSequence()
            .firstOrNull { it.startsWith("inte ") || it.startsWith("inte_") }

        // /sys/module/inte 是否注册
        val sysOut = Shell.runSu(
            "-c",
            "if [ -d /sys/module/inte ]; then echo YES; else echo NO; fi",
            timeoutMs = 4000
        ).result?.out.orEmpty().trim()
        val registered = sysOut.contains("YES")

        if (line == null && !registered) return null

        val parts = line?.trim()?.split(Regex("\\s+")).orEmpty()
        val moduleName = parts.getOrNull(0)?.let { "$it.ko" }
        val mem = parts.getOrNull(1)?.toLongOrNull()

        return DriverInfo(
            moduleName = moduleName,
            memoryBytes = mem,
            sysfsPath = if (registered) "/sys/module/inte" else null,
            sysfsRegistered = registered,
            modulesLine = line?.trim(),
        )
    }
}
