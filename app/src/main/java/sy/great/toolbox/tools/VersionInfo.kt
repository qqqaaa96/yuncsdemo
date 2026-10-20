package sy.great.toolbox.tools

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本信息与公告（服务端 version.json）。
 *
 * 部署：把 version.json 放在网站根目录（与 api.php 同级）
 * 访问：https://你的域名/version.json
 */
object VersionInfo {

    data class Announcement(
        val enabled: Boolean = false,
        val id: String = "",
        val title: String = "",
        val content: String = "",
        val showOnce: Boolean = true
    )

    data class Info(
        val ok: Boolean = false,
        val versionCode: Int = 0,
        val versionName: String = "",
        val downloadUrl: String = "",
        val forceUpdate: Boolean = false,
        val minSupportedCode: Int = 0,
        val publishedAt: String = "",
        val changelog: List<String> = emptyList(),
        val announcement: Announcement = Announcement(),
        val error: String = ""
    )

    /** 拉取版本信息 */
    fun fetch(timeoutMs: Int = 15000): Info {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("${AuthApi.BASE}/version.json")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doInput = true
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode !in 200..299) {
                return Info(error = "HTTP " + conn.responseCode)
            }
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            if (text.isBlank()) return Info(error = "服务器返回为空")

            val j = JSONObject(text)

            val logArr = j.optJSONArray("changelog")
            val logs = ArrayList<String>()
            if (logArr != null) {
                for (i in 0 until logArr.length()) {
                    val s = logArr.optString(i, "")
                    if (s.isNotBlank()) logs.add(s)
                }
            }

            val an = j.optJSONObject("announcement")
            val ann = if (an != null) Announcement(
                enabled = an.optBoolean("enabled", false),
                id = an.optString("id", ""),
                title = an.optString("title", ""),
                content = an.optString("content", ""),
                showOnce = an.optBoolean("showOnce", true)
            ) else Announcement()

            Info(
                ok = true,
                versionCode = j.optInt("versionCode", 0),
                versionName = j.optString("versionName", ""),
                downloadUrl = j.optString("downloadUrl", ""),
                forceUpdate = j.optBoolean("forceUpdate", false),
                minSupportedCode = j.optInt("minSupportedCode", 0),
                publishedAt = j.optString("publishedAt", ""),
                changelog = logs,
                announcement = ann
            )
        } catch (e: Throwable) {
            Info(error = e.message ?: e.javaClass.simpleName)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }
}
