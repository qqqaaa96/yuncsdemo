package sy.great.toolbox.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.net.NetworkInterface

/**
 * VPN / 代理检测。
 *
 * 思路参考 yourvpndead 项目，但只保留能真正生效的检查：
 *   1. NetworkCapabilities：TRANSPORT_VPN + toString 里的 IS_VPN / VpnTransportInfo
 *   2. 网络接口名：tun* / tap* / wg* / ppp* / ipsec*
 *   3. 系统代理变量：http.proxyHost / socksProxyHost
 *   4. 路由表：/proc/net/route 默认路由是否走 VPN 接口
 *   5. 已知 VPN 应用包名
 *
 * 注意：拿不到用户真实 IP —— VPN 开启后本地只有虚拟内网地址，
 *       公网出口 IP 只有服务端能看到（而且那是 VPN 出口，不是原始 IP）。
 */
object VpnDetector {

    /** 一次检测结果 */
    data class Result(
        val detected: Boolean,
        /** 命中原因，多个用 | 连接 */
        val reasons: List<String>,
        /** 命中时取到的 VPN 虚拟地址（可能为空） */
        val vpnIps: List<String>,
        /** 命中的接口名 */
        val interfaces: List<String>,
        /** 命中的 VPN 应用 */
        val apps: List<String>
    ) {
        fun summary(): String = if (reasons.isEmpty()) "" else reasons.joinToString(" | ")
    }

    /** 已知 VPN / 代理类应用 */
    private val KNOWN_VPN_APPS = mapOf(
        "com.v2ray.ang" to "v2rayNG",
        "io.nekohasekai.sfa" to "sing-box",
        "app.hiddify.com" to "Hiddify",
        "com.github.metacubex.clash.meta" to "Clash Meta",
        "com.github.shadowsocks" to "Shadowsocks",
        "com.happproxy" to "HAPP VPN",
        "io.github.saeeddev94.xray" to "XrayNG",
        "moe.nb4a" to "NekoBox",
        "io.github.dovecoteescapee.byedpi" to "ByeDPI",
        "org.outline.android.client" to "Outline",
        "com.psiphon3" to "Psiphon",
        "org.getlantern.lantern" to "Lantern",
        "com.wireguard.android" to "WireGuard",
        "com.strongswan.android" to "strongSwan",
        "org.torproject.android" to "Orbot",
        "org.amnezia.vpn" to "Amnezia VPN",
    )

    /**
     * 完整检测。所有异常都吞掉，绝不让检测本身导致崩溃。
     */
    fun detect(ctx: Context): Result {
        val reasons = ArrayList<String>()
        val vpnIps = ArrayList<String>()
        val hitIfaces = ArrayList<String>()
        val hitApps = ArrayList<String>()

        // ---- 1. NetworkCapabilities ----
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val caps = if (cm != null && net != null) cm.getNetworkCapabilities(net) else null
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    reasons.add("TRANSPORT_VPN")
                }
                val s = caps.toString()
                if (s.contains("IS_VPN")) reasons.add("IS_VPN")
                if (s.contains("VpnTransportInfo")) reasons.add("VpnTransportInfo")
            }
        } catch (_: Throwable) {
        }

        // ---- 2. 网络接口 ----
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (iface in ifaces) {
                if (!iface.isUp) continue
                val name = iface.name.lowercase()
                val isVpn = name.matches(Regex("^tun\\d+")) ||
                        name.matches(Regex("^tap\\d+")) ||
                        name.matches(Regex("^wg\\d+")) ||
                        name.matches(Regex("^ppp\\d+")) ||
                        name.matches(Regex("^ipsec.*")) ||
                        name.startsWith("tun") || name.startsWith("ppp")
                if (!isVpn) continue

                hitIfaces.add(iface.name)
                reasons.add("iface:" + iface.name)
                try {
                    for (addr in iface.inetAddresses) {
                        val host = addr.hostAddress ?: continue
                        vpnIps.add(host.substringBefore('%'))
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }

        // ---- 3. 系统代理 ----
        try {
            val httpHost = System.getProperty("http.proxyHost")?.takeIf { it.isNotBlank() }
            val socksHost = System.getProperty("socksProxyHost")?.takeIf { it.isNotBlank() }
            if (httpHost != null) reasons.add("proxy:http=$httpHost")
            if (socksHost != null) reasons.add("proxy:socks=$socksHost")
        } catch (_: Throwable) {
        }

        // ---- 4. 默认路由 ----
        try {
            val f = File("/proc/net/route")
            if (f.canRead()) {
                for (line in f.readLines().drop(1)) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 8) continue
                    // destination == 00000000 且 mask == 00000000 → 默认路由
                    if (parts[1] != "00000000") continue
                    val iface = parts[0].lowercase()
                    val normal = iface.startsWith("wlan") || iface.startsWith("rmnet") ||
                            iface.startsWith("eth") || iface.startsWith("lo") ||
                            iface.startsWith("ccmni") || iface.startsWith("pdp")
                    if (!normal) reasons.add("route:" + parts[0])
                }
            }
        } catch (_: Throwable) {
        }

        // ---- 5. 已知 VPN 应用 ----
        try {
            val pm = ctx.packageManager
            for ((pkg, label) in KNOWN_VPN_APPS) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    hitApps.add(label)
                } catch (_: Throwable) {
                    // 未安装
                }
            }
            // 安装了 VPN 应用只记录，不单独作为“已开启 VPN”的判定
            // （否则正常装了 WireGuard 但没连的用户会被误杀）
        } catch (_: Throwable) {
        }

        // 判定：接口 / 传输 / 代理 / 路由 任一命中就算开着 VPN。
        // 仅安装应用不算。
        val coreHit = reasons.any {
            it == "TRANSPORT_VPN" || it == "IS_VPN" || it == "VpnTransportInfo" ||
                    it.startsWith("iface:") || it.startsWith("proxy:") || it.startsWith("route:")
        }

        return Result(
            detected = coreHit,
            reasons = reasons,
            vpnIps = vpnIps.distinct(),
            interfaces = hitIfaces,
            apps = hitApps
        )
    }
}
