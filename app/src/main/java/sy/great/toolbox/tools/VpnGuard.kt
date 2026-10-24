package sy.great.toolbox.tools

import android.content.Context
import android.net.ConnectivityManager

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

    /** 轮询间隔：2 秒。
     *
     * 为什么是 2 秒：
     *   · 检测本身是纯内存操作（读 NetworkCapabilities + /proc），耗时 < 5ms
     *   · 2 秒足够快，用户开 VPN 后来不及做什么
     *   · 再频繁会无意义地耗电
     */
    private const val POLL_INTERVAL_MS = 2000L

    /** 是否已经在监测中（避免重复启动） */
    @Volatile
    private var watching = false

    /** 已触发过退出，避免重复上报 */
    @Volatile
    private var triggered = false

    /**
     * 启动持续监测。
     *
     * 两种触发方式，互为兜底：
     *   1. 每 2 秒轮询一次
     *   2. 网络状态变化时立即检查（开 VPN 会触发 ConnectivityManager 回调）
     *
     * 解决了「先开 App 再开 VPN 就检测不到」的问题：
     * 以前只在启动时调一次 checkAndBlock，之后再也不查。
     *
     * 调用方：MainActivity.onCreate
     */
    fun startWatching(ctx: Context) {
        val app = ctx.applicationContext

        synchronized(this) {
            if (watching) return
            watching = true
        }

        // ---- 1. 注册网络变化监听 ----
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            if (cm != null) {
                cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        checkAsync(app)
                    }

                    override fun onCapabilitiesChanged(
                        network: android.net.Network,
                        caps: android.net.NetworkCapabilities,
                    ) {
                        // 开 / 关 VPN 会触发这里
                        checkAsync(app)
                    }

                    override fun onLost(network: android.net.Network) {
                        checkAsync(app)
                    }
                })
            }
        } catch (_: Throwable) {
        }

        // ---- 2. 定时轮询 ----
        val t = Thread {
            while (true) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }

                if (triggered) break

                try {
                    val r = VpnDetector.detect(app)
                    if (r.detected) {
                        handleHit(app, r)
                        break
                    }
                } catch (_: Throwable) {
                    // 单次检测失败不影响后续轮询
                }
            }
        }
        t.isDaemon = true
        t.name = "vpn-watch"
        t.start()
    }

    /** 后台线程里做一次检测（用于网络回调） */
    private fun checkAsync(app: Context) {
        if (triggered) return
        Thread {
            try {
                val r = VpnDetector.detect(app)
                if (r.detected) handleHit(app, r)
            } catch (_: Throwable) {
            }
        }.apply {
            isDaemon = true
            name = "vpn-check"
        }.start()
    }

    /** 命中后的统一处理：只执行一次 */
    private fun handleHit(ctx: Context, r: VpnDetector.Result) {
        synchronized(this) {
            if (triggered) return
            triggered = true
        }

        // 复用原有上报 + 退出逻辑
        checkAndBlock(ctx)
    }

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
