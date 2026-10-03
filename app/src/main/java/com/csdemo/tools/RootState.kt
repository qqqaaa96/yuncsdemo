package com.csdemo.tools

/**
 * Root 检测结果的进程级缓存。
 *
 * 目的：主页的 Root 检测只跑一次。
 * 切换页面回来、重组、甚至页面重建，都不会重新执行 su 检测
 * （除非进程重启，或调用 refresh()）。
 *
 * 用 Compose 的 mutableStateOf，检测完成后会自动触发重组。
 */
object RootState {

    /** 最近一次检测结果，null 表示尚未检测完成 */
    val report = androidx.compose.runtime.mutableStateOf<RootCheck.Report?>(null)

    /** SELinux 状态（同一次加载里一起取） */
    val selinux = androidx.compose.runtime.mutableStateOf("Enforcing")

    /**
     * 当前运行身份：
     *   ROOT     —— 有 root（绿卡）
     *   ADB_SHELL—— 有 Shizuku adb（紫卡）
     *   USER     —— 都没有（蓝卡）
     */
    val mode = androidx.compose.runtime.mutableStateOf(AdbShell.Mode.USER)

    /** Shizuku 可用但未授权时为 true（主页可提示去授权） */
    val adbNeedsPermission = androidx.compose.runtime.mutableStateOf(false)

    /**
     * 用户当前选用/生效的身份。
     *
     * 与 mode 的区别：
     *   mode       —— “探测到具备什么能力”（root + adb 都可能有）
     *   activeMode —— “当前实际使用哪一种”
     *
     * 当 root 与 adb 同时具备时，用户可在主页卡片上切换，
     * 切换结果写入 activeMode，并且**所有功能都按 activeMode 决定提权方式**：
     *   选 ADB  → 不再使用 su（Root 检测会如实报告“当前为 ADB 模式”）
     *   选 ROOT → 真实走 su
     */
    val activeMode = androidx.compose.runtime.mutableStateOf(AdbShell.Mode.USER)

    /** 当前是否应该走 su 提权：只有在 root 能力存在且用户选用 root 时为 true */
    fun useRoot(): Boolean = activeMode.value == AdbShell.Mode.ROOT

    /** 当前是否以 ADB(shell) 身份运行 */
    fun useAdb(): Boolean = activeMode.value == AdbShell.Mode.ADB_SHELL

    /**
     * 主界面当前选中的底部 tab（0 主页 / 1 常用功能 / 2 工具 / 3 设置）。
     * 提到全局，使得从子页面返回主界面时能恢复到原来的 tab，
     * 而不是被重置回“主页”。
     */
    val mainTab = androidx.compose.runtime.mutableStateOf(0)

    /** 是否正在检测（防止并发重复触发） */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 每次进入主页时调用：重新检测一次 root。
     *
     * 与之前“只检测一次”不同：现在是“每次进入都重新检测”，
     * 但保留上一次的结果用于显示（不置空），避免重新检测期间界面闪烁。
     * 并发保护：若正在检测中，则不重复发起。
     */
    fun detectOnEnter() {
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                val r = RootCheck.scan()
                val s = com.csdemo.tools.Selinux.read().mode
                selinux.value = when (s) {
                    com.csdemo.tools.Selinux.Mode.ENFORCING -> "Enforcing"
                    com.csdemo.tools.Selinux.Mode.PERMISSIVE -> "Permissive"
                    com.csdemo.tools.Selinux.Mode.DISABLED -> "Disabled"
                    else -> "Unknown"
                }
                report.value = r

                // mode：记录“具备什么能力”（root > adb > user），供卡片选择使用
                val detected = AdbShell.detectMode(r.granted)
                mode.value = detected
                adbNeedsPermission.value = !r.granted && AdbShell.needsRequest()

                // activeMode：用户当前实际使用的身份。
                //
                // 规则：
                //   · 若用户从未选过（还是 USER）→ 自动采用探测结果；
                //   · 若用户选过 → 保留；但若该能力已不存在（如失去 root），
                //     则回退到探测结果，避免“选了 root 却已无 root”的悬空状态。
                val current = activeMode.value
                val stillValid = when (current) {
                    AdbShell.Mode.ROOT -> r.granted
                    AdbShell.Mode.ADB_SHELL -> AdbShell.granted()
                    AdbShell.Mode.USER -> true
                }
                if (current == AdbShell.Mode.USER || !stillValid) {
                    activeMode.value = detected
                }
            } catch (e: Exception) {
                // 失败不覆盖旧结果；仅允许下次重试
            } finally {
                running.set(false)
            }
        }.start()
    }

    /** 兼容旧调用名 */
    fun ensureLoaded() = detectOnEnter()

    /** 手动清空并重新检测 */
    fun refresh() {
        report.value = null
        running.set(false)
        detectOnEnter()
    }
}
