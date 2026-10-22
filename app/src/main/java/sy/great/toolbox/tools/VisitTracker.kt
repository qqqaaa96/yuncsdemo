package sy.great.toolbox.tools

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 访问上报。
 *
 * 用户打开 App 就上报一次，**不需要登录**。
 * 服务端根据请求来源 IP 记录地理位置，用于后台的访问分布地图。
 *
 * 设计原则：
 *   · 不阻塞启动：在后台线程发，绝不 await
 *   · 不重复上报：同一进程生命周期只报一次（AtomicBoolean）
 *   · 不打扰用户：任何失败都静默吞掉，不弹提示不写日志
 *   · 不耗流量：一次 GET，超时 8 秒，没有重试
 *
 * 隐私：
 *   只上报设备指纹的哈希（与注册配额用的是同一个，不可逆），
 *   以及 App 版本号。不上报任何其他信息。
 */
object VisitTracker {

    private val reported = AtomicBoolean(false)

    /**
     * 上报一次访问。
     *
     * 在 Application 或 MainActivity.onCreate 里调一次即可，
     * 重复调用会被忽略。
     *
     * @param hint 附加信息，会作为 device / ver 参数带给服务器
     */
    fun reportOnce(ctx: Context, hint: Hint = Hint()) {
        if (!reported.compareAndSet(false, true)) return

        val app = ctx.applicationContext

        Thread {
            try {
                doReport(app, hint)
            } catch (_: Throwable) {
                // 上报失败不影响 App 任何功能，静默丢弃
            }
        }.apply {
            isDaemon = true
            name = "visit-report"
        }.start()
    }

    /** 上报参数 */
    data class Hint(
        val device: String = "",
        val ver: String = ""
    )

    private fun doReport(ctx: Context, hint: Hint) {
        val device = hint.device.ifEmpty { DeviceId.get(ctx) }
        val ver = hint.ver.ifEmpty {
            val n = AppVersion.name(ctx)
            if (n.isNotEmpty()) n else AppVersion.code(ctx).toString()
        }

        val token = AuthApi.token(ctx)

        val qs = buildString {
            append("&device=").append(enc(device))
            append("&ver=").append(enc(ver))
        }

        val url = URL(AuthApi.BASE + "/api.php?action=visit" + qs)
        var conn: HttpURLConnection? = null

        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 8000
                doInput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "csdemo-app/" + ver + " (android)")
                if (token.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer " + token)
                }
            }

            // 只关心是否成功，不解析返回内容
            val code = conn.responseCode
            if (code in 200..299) {
                try {
                    conn.inputStream?.close()
                } catch (_: Throwable) {
                }
            }
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun enc(s: String): String =
        try {
            java.net.URLEncoder.encode(s, "UTF-8")
        } catch (_: Throwable) {
            ""
        }
}
