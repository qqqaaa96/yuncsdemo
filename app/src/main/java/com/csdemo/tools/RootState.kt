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
