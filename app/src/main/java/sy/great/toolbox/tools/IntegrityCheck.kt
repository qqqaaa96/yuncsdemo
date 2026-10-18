package sy.great.toolbox.tools

/**
 * 内核完整性检测模块（oplus_kernel_security_check）验证与阻断。
 *
 * 本文件实现文档中“App 可真实执行”的部分：
 *
 *   只读验证（100% 安全）
 *     · 模块是否加载 / 当前状态
 *     · 三个 /proc 节点存在性
 *     · boot_stage 门控当前状态
 *     · 事件列表（/proc/inte_ko）
 *
 *   破坏性操作（每项调用方必须二次确认）
 *     · 卸载模块（rmmod）
 *
 * 说明：文档里的 1.2/1.3/1.4/2.3/4 等场景需要修改内核模块源码，
 * 不是 App 能实现的，本文件不包含，也不会伪造结果。
 */
object IntegrityCheck {

    const val MODULE_NAME = "oplus_kernel_security_check"

    /** 文档中的三个保护节点 */
    val NODES = listOf(
        "/proc/inte_ko",
        "/proc/inte_status",
        "/proc/inte_systbl",
    )

    /** 单项验证结果 */
    data class Item(
        val title: String,
        /** true=通过 / false=不通过 / null=无法判断 */
        val ok: Boolean?,
        val detail: String,
    )

    /** 完整报告 */
    data class Report(
        val hasRoot: Boolean,
        val items: List<Item>,
        /** 模块在 /sys/module 下的真实目录名（没加载为 null） */
        val sysfsDir: String?,
        /** /proc/modules 里那一行原文 */
        val modulesLine: String?,
        /** /proc/inte_status 原文（可能为空） */
        val statusRaw: String,
        /** /proc/inte_ko 的事件条数（无法读为 null） */
        val eventCount: Int?,
    ) {
        /** 模块是否处于已加载状态 */
        val loaded: Boolean get() = sysfsDir != null
    }

    // ---------------- 只读检测 ----------------

    /**
     * 执行验证。阻塞调用，必须在 IO 线程。
     *
     * 没 root 时只返回“需要权限”，不跑任何提权命令。
     */
    fun verify(): Report {
        if (!RootState.useRoot()) {
            return Report(
                hasRoot = false,
                items = listOf(Item("需要 Root", null, "本功能需读取受保护的内核节点")),
                sysfsDir = null,
                modulesLine = null,
                statusRaw = "",
                eventCount = null,
            )
        }

        val items = ArrayList<Item>()

        // ---- 1) 模块是否加载 ----
        val sysList = sh("ls -1 /sys/module 2>/dev/null")
        val sysDir = sysList.lineSequence()
            .map { it.trim() }
            .firstOrNull { it == MODULE_NAME }

        // ---- 2) /proc/modules 状态 ----
        val modulesAll = sh("cat /proc/modules 2>/dev/null")
        val modLine = modulesAll.lineSequence()
            .firstOrNull { it.trim().split(Regex("\\s+")).firstOrNull() == MODULE_NAME }
            ?.trim()

        items.add(
            Item(
                "模块已加载",
                sysDir != null || modLine != null,
                when {
                    sysDir != null && modLine != null -> "sysfs 与 /proc/modules 均确认"
                    sysDir != null -> "仅 sysfs 存在（/proc/modules 无记录）"
                    modLine != null -> "仅 /proc/modules 有记录"
                    else -> "未检测到模块"
                }
            )
        )

        // ---- 3) 运行状态（Live / Loading / Unloading） ----
        val modParts = modLine?.split(Regex("\\s+")).orEmpty()
        val state = modParts.getOrNull(4)
        items.add(
            Item(
                "模块运行状态",
                state == "Live",
                if (state != null) state else "未读到状态字段"
            )
        )

        // ---- 4) 三个保护节点 ----
        val existsFlags = NODES.map { p ->
            val out = sh("if [ -e $p ]; then echo Y; else echo N; fi").trim()
            when {
                out == "Y" -> true
                out == "N" -> false
                else -> null
            }
        }
        items.add(
            Item(
                "三个保护节点",
                if (existsFlags.all { it == true }) true else if (existsFlags.any { it == false }) false else null,
                NODES.mapIndexed { i, p ->
                    p + ": " + when (existsFlags[i]) {
                        true -> "存在"
                        false -> "不存在"
                        null -> "无法判断"
                    }
                }.joinToString("；")
            )
        )

        // ---- 5) boot_stage 门控状态 ----
        val statusRaw = sh("cat /proc/inte_status 2>/dev/null").trim()
        items.add(
            Item(
                "boot_stage 门控",
                when {
                    statusRaw.isBlank() -> null
                    statusRaw.contains("BOOT_COMPLETE") -> true
                    else -> false
                },
                when {
                    statusRaw.isBlank() -> "读不到 /proc/inte_status"
                    statusRaw.contains("BOOT_COMPLETE") -> "已置位（检测已启动）"
                    else -> "未置位（检测可能未启动）｜ 原文：" + statusRaw.take(120)
                }
            )
        )

        // ---- 6) 事件列表容量 ----
        val koRaw = sh("cat /proc/inte_ko 2>/dev/null")
        val eventCount = when {
            koRaw.isBlank() -> null
            else -> koRaw.lineSequence().count { it.isNotBlank() }
        }
        items.add(
            Item(
                "事件列表",
                eventCount != null,
                when {
                    eventCount == null -> "读不到 /proc/inte_ko"
                    eventCount >= 10 -> "已有 " + eventCount + " 条（接近/达到上限 10）"
                    else -> "当前 " + eventCount + " 条"
                }
            )
        )

        return Report(
            hasRoot = true,
            items = items,
            sysfsDir = sysDir?.let { "/sys/module/" + it },
            modulesLine = modLine,
            statusRaw = statusRaw,
            eventCount = eventCount,
        )
    }

    // ---------------- 破坏性操作 ----------------

    /**
     * 卸载模块（rmmod）。
     *
     * 返回 (是否成功, 说明)。
     * 调用方必须先二次确认。
     */
    fun unloadModule(): Pair<Boolean, String> {
        if (!RootState.useRoot()) return false to "需要 Root 权限"

        val r = Shell.runSu("-c", "rmmod $MODULE_NAME 2>&1", timeoutMs = 8000)
        val out = (r.result?.out.orEmpty() + r.result?.err.orEmpty()).trim()
        val code = r.result?.code ?: -1

        if (code == 0) return true to "已执行 rmmod"

        // 给出可读的原因
        return false to when {
            out.contains("not found", ignoreCase = true) -> "模块未加载"
            out.contains("in use", ignoreCase = true) -> "模块被占用（in use）"
            out.contains("permission", ignoreCase = true) -> "权限不足"
            out.contains("operation not permitted", ignoreCase = true) -> "操作被拒绝（可能被 LSM/内核保护）"
            out.isBlank() -> "rmmod 失败（无输出，退出码 " + code + "）"
            else -> out.take(200)
        }
    }

    // ---------------- 空壳化阻断（mount --bind） ----------------

    /** 单项阻断结果 */
    data class BindResult(
        val path: String,
        val ok: Boolean,
        val detail: String,
    )

    /**
     * 把每个 /proc/inte_* 绑定到 /dev/null，让读取方拿到空内容。
     *
     * 安全设计（按优先级）：
     *   1) 只做 bind mount，可逆，不卸载模块、不改内核、不动 SELinux；
     *   2) 每一项独立：失败立即 umount 回滚该项，不影响其它项；
     *   3) 挂载后回读验证，内容必须为空；不是空就当失败并回滚。
     *
     * 阻塞调用，必须在 IO 线程。
     */
    fun blockByBind(): List<BindResult> {
        if (!RootState.useRoot()) {
            return listOf(BindResult("-", false, "需要 Root 权限"))
        }
        val out = ArrayList<BindResult>()
        for (p in NODES) {
            // 1) 节点不存在就跳过
            val exist = sh("if [ -e $p ]; then echo Y; else echo N; fi").trim()
            if (exist != "Y") {
                out.add(BindResult(p, false, "节点不存在，跳过"))
                continue
            }

            // 2) 已绑过则先解绑，避免重复挂载堆叠
            sh("umount $p 2>/dev/null")

            // 3) 执行 bind
            val bindOut = sh("mount --bind /dev/null $p 2>&1")
            if (bindOut.isNotBlank() && bindOut.contains("denied", ignoreCase = true)) {
                out.add(BindResult(p, false, "挂载被拒绝：" + bindOut.take(120)))
                continue
            }

            // 4) 回读验证：内容必须为空
            val after = sh("cat $p 2>/dev/null")
            if (after.isBlank()) {
                out.add(BindResult(p, true, "已阻断（读取为空）"))
            } else {
                // 回滚
                sh("umount $p 2>/dev/null")
                out.add(BindResult(p, false, "回读不为空，已回滚。内容前 60 字：" + after.take(60)))
            }
        }
        return out
    }

    /**
     * 恢复：解除所有 /proc/inte_* 的 bind 挂载。
     *
     * 逐项 umount，并回读后确认“已不再是空”（说明退回到原始节点）。
     */
    fun unblockByBind(): List<BindResult> {
        if (!RootState.useRoot()) {
            return listOf(BindResult("-", false, "需要 Root 权限"))
        }
        val out = ArrayList<BindResult>()
        for (p in NODES) {
            val exist = sh("if [ -e $p ]; then echo Y; else echo N; fi").trim()
            if (exist != "Y") {
                out.add(BindResult(p, false, "节点不存在"))
                continue
            }
            sh("umount $p 2>/dev/null")
            // 检查是否还挂载着
            val stillMounted = sh("grep -c \" $p \" /proc/mounts 2>/dev/null").trim()
            if (stillMounted == "0") {
                out.add(BindResult(p, true, "已恢复"))
            } else {
                out.add(BindResult(p, false, "解除失败（可能被多次挂载）"))
            }
        }
        return out
    }

    /** 当前是否处于阻断状态（任一节点读到空内容且已挂载） */
    fun isBlocked(): Boolean {
        if (!RootState.useRoot()) return false
        return NODES.any { p ->
            val mounted = sh("grep -c \" $p \" /proc/mounts 2>/dev/null").trim()
            mounted != "0" && mounted.isNotBlank()
        }
    }

    // ---------------- 工具 ----------------

    /** 以内 root 身份跑一条命令，返回 stdout */
    private fun sh(cmd: String): String = try {
        Shell.runSu("-c", cmd, timeoutMs = 4000).result?.out.orEmpty()
    } catch (_: Throwable) {
        ""
    }
}
