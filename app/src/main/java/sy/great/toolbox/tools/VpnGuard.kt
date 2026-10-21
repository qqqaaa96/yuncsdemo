package sy.great.toolbox.tools

import android.content.Context

/**
 * VPN 强制退出。
 *
 * 检测到 VPN → 先同步上报（最多等 1.5 秒）→ 彻底杀进程。
 *
 * 为什么不能异步上报：
 *   System.exit 会立刻杀掉所有线程，异步请求发不完。
 *   但也不能无限等 —— 网络差时用户会卡在空白页。
 *   所以用“后台线程 + 最多等 1.5 秒”的折中。
 *
 * 为什么要延迟 300ms 再退：
 *   给上报请求留一点建立连接的时间，同时避免界面看起来“闪一下就没”。
 */
object VpnGuard {

    /** 上报最长等待（毫秒） */
    private const val REPORT_WAIT_MS = 1500L

    /**
     * 在后台线程做一次检测。
     *
     * @param onHit 命中时的回调（在调用方线程），用于显示提示、记录等
     * @return true 表示检测到 VPN（此时已经安排好退出）
     */
    fun checkAndBlock(ctx: Context, onHit: ((VpnDetector.Result) -> Unit)? = null): Boolean {
        val r = try {
            VpnDetector.detect(ctx)
        } catch (_: Throwable) {
            return false
        }
        if (!r.detected) return false

        onHit?.invoke(r)

        // 上报 + 退出，放在独立线程，不阻塞调用方
        Thread {
            try {
                val devId = try { DeviceId.get(ctx) } catch (_: Throwable) { "" }
                val t = Thread {
                    try {
                        AuthApi.reportVpn(
                            ctx = ctx,
                            vpnType = r.summary().take(500),
                            vpnIps = r.vpnIps.joinToString(",").take(200),
                            vpnApps = r.apps.joinToString(",").take(200),
                            deviceId = devId
                        )
                    } catch (_: Throwable) {
                    }
                }
                t.start()
                t.join(REPORT_WAIT_MS)
            } catch (_: Throwable) {
            }

            forceExit()
        }.start()

        return true
    }

    /**
     * 彻底退出 App。
     *
     * 先 finishAffinity 清掉所有 Activity，再 System.exit，
     * 保证从最近任务里也看不到残留窗口。
     */
    private fun forceExit(): Unit {
        try {
            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (_: Throwable) {
        }
        System.exit(0)
    }
}
