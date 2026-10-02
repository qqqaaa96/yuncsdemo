package com.csdemo.tools

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 网络诊断。全部真实发包，没有假数据。
 */
object Net {

    data class PingLine(val text: String, val ok: Boolean)

    fun ping(host: String, count: Int = 4, onLine: (PingLine) -> Unit) {
        try {
            val p = ProcessBuilder("ping", "-c", count.toString(), "-i", "0.5", host)
                .redirectErrorStream(true)
                .start()
            val r = p.inputStream.bufferedReader()
            var line: String? = r.readLine()
            while (line != null) {
                onLine(PingLine(line, true))
                line = r.readLine()
            }
            p.waitFor()
        } catch (e: Exception) {
            onLine(PingLine("执行失败: ${e.message}", false))
        }
    }

    fun resolve(host: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val addrs = InetAddress.getAllByName(host)
            addrs.forEach { a ->
                val type = if (a is java.net.Inet6Address) "AAAA" else "A"
                out += type to a.hostAddress
            }
        } catch (e: Exception) {
            out += "错误" to (e.message ?: "解析失败")
        }
        return out
    }

    fun reverse(ip: String): String = try {
        InetAddress.getByName(ip).canonicalHostName
    } catch (e: Exception) {
        "解析失败"
    }

    private val SERVICES = mapOf(
        20 to "ftp-data", 21 to "ftp", 22 to "ssh", 23 to "telnet", 25 to "smtp",
        53 to "dns", 67 to "dhcp", 80 to "http", 110 to "pop3", 123 to "ntp",
        135 to "msrpc", 139 to "netbios", 143 to "imap", 161 to "snmp", 443 to "https",
        445 to "smb", 465 to "smtps", 587 to "smtp", 993 to "imaps", 995 to "pop3s",
        1433 to "mssql", 1521 to "oracle", 3306 to "mysql", 3389 to "rdp",
        5432 to "postgres", 5900 to "vnc", 6379 to "redis", 8080 to "http-alt",
        8443 to "https-alt", 8888 to "http-alt", 9000 to "cslistener", 27017 to "mongodb"
    )

    fun isOpen(host: String, port: Int, timeoutMs: Int = 800): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    } catch (e: Exception) {
        false
    }

    fun serviceName(port: Int): String = SERVICES[port] ?: "unknown"

    fun banner(host: String, port: Int, timeoutMs: Int = 1200): String = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            val buf = ByteArray(512)
            val n = s.getInputStream().read(buf)
            if (n > 0) String(buf, 0, n).trim() else ""
        }
    } catch (e: Exception) {
        ""
    }

    data class HttpInfo(
        val url: String,
        val statusLine: String,
        val headers: List<Pair<String, String>>,
        val server: String,
        val poweredBy: String,
        val contentType: String,
        val length: String,
        val secure: Boolean,
        val error: String?
    )

    fun http(url: String, useHead: Boolean = true): HttpInfo {
        val secure = url.startsWith("https")
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = if (useHead) "HEAD" else "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Toolbox)")
            conn.connect()

            val headers = ArrayList<Pair<String, String>>()
            for ((k, values) in conn.headerFields) {
                if (k == null || values == null) continue
                for (v in values) headers.add(k to v)
            }

            HttpInfo(
                url = url,
                statusLine = "HTTP " + conn.responseCode + " " + conn.responseMessage,
                headers = headers,
                server = conn.getHeaderField("Server").orEmpty(),
                poweredBy = conn.getHeaderField("X-Powered-By").orEmpty(),
                contentType = conn.getHeaderField("Content-Type").orEmpty(),
                length = conn.getHeaderField("Content-Length").orEmpty(),
                secure = secure,
                error = null
            )
        } catch (e: Exception) {
            HttpInfo(url, "", emptyList(), "", "", "", "", secure, e.message ?: "请求失败")
        }
    }

    fun arpTable(): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        val text = Shell.readFile("/proc/net/arp") ?: return out
        text.lineSequence().drop(1).forEach { ln ->
            val p = ln.trim().split(Regex("\\s+"))
            if (p.size >= 4 && p[0].contains('.')) {
                out += Triple(p[0], p[3], if (p.size > 5) p[5] else "")
            }
        }
        return out
    }

    fun localIpv4(): Pair<String, Int>? {
        try {
            val nifs = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in java.util.Collections.list(nifs)) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val a = ia.address
                    if (a is java.net.Inet4Address) {
                        return a.hostAddress to ia.networkPrefixLength.toInt()
                    }
                }
            }
        } catch (e: Exception) {
        }
        return null
    }

    suspend fun sweep(prefix: String, onFound: (String) -> Unit) = coroutineScope {
        val ports = listOf(80, 443, 22, 445, 62078, 5555, 8080)
        for (i in 1..254) {
            launch(Dispatchers.IO) {
                val ip = "$prefix.$i"
                if (ports.any { isOpen(ip, it, 350) }) onFound(ip)
            }
        }
    }
}
