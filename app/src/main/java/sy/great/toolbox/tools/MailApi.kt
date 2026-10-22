package sy.great.toolbox.tools

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 站内信网络层。
 *
 * 接口：
 *   GET  ?action=mail_list&limit=100   —— 列表 + 未读数
 *   GET  ?action=mail_unread           —— 只取未读数（用于角标刷新）
 *   GET  ?action=mail_read&id=N        —— 标记已读
 *   GET  ?action=mail_read_all         —— 全部已读
 *   GET  ?action=mail_delete&id=N      —— 删除
 *
 * 全部需要登录（带 Bearer token）。
 */
object MailApi {

    data class Mail(
        val id: Int,
        val title: String,
        val body: String,
        /** normal / important / warning */
        val level: String,
        val sender: String,
        val createdAt: Int,
        val read: Boolean,
        val emailed: Boolean,
    )

    data class ListResult(
        val ok: Boolean,
        val error: String = "",
        val items: List<Mail> = emptyList(),
        val unread: Int = 0,
    )

    // ---------- 列表 ----------

    fun list(ctx: Context, limit: Int = 100): ListResult {
        val token = AuthApi.token(ctx)
        if (token.isEmpty()) {
            return ListResult(false, "未登录")
        }

        val r = get(ctx, "mail_list&limit=$limit", token)
        if (r == null) return ListResult(false, "网络错误")

        if (!r.optBoolean("ok", false)) {
            return ListResult(false, r.optString("error", "拉取失败"))
        }

        val data = r.optJSONObject("data")
        val arr = data?.optJSONArray("items")
        val items = ArrayList<Mail>()

        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                items.add(
                    Mail(
                        id = o.optInt("id", 0),
                        title = o.optString("title", ""),
                        body = o.optString("body", ""),
                        level = o.optString("level", "normal"),
                        sender = o.optString("sender", "系统"),
                        createdAt = o.optInt("created_at", 0),
                        read = o.optBoolean("read", false),
                        emailed = o.optBoolean("emailed", false),
                    )
                )
            }
        }

        return ListResult(
            ok = true,
            items = items,
            unread = data?.optInt("unread", 0) ?: 0,
        )
    }

    // ---------- 未读数 ----------

    fun unread(ctx: Context): Int {
        val token = AuthApi.token(ctx)
        if (token.isEmpty()) return 0

        val r = get(ctx, "mail_unread", token) ?: return 0
        if (!r.optBoolean("ok", false)) return 0
        return r.optJSONObject("data")?.optInt("unread", 0) ?: 0
    }

    // ---------- 动作 ----------

    fun markRead(ctx: Context, id: Int): Int {
        val token = AuthApi.token(ctx)
        if (token.isEmpty()) return -1
        val r = get(ctx, "mail_read&id=$id", token) ?: return -1
        if (!r.optBoolean("ok", false)) return -1
        return r.optJSONObject("data")?.optInt("unread", 0) ?: 0
    }

    fun markAllRead(ctx: Context): Boolean {
        val token = AuthApi.token(ctx)
        if (token.isEmpty()) return false
        val r = get(ctx, "mail_read_all", token) ?: return false
        return r.optBoolean("ok", false)
    }

    fun delete(ctx: Context, id: Int): Boolean {
        val token = AuthApi.token(ctx)
        if (token.isEmpty()) return false
        val r = get(ctx, "mail_delete&id=$id", token) ?: return false
        return r.optBoolean("ok", false)
    }

    // ---------- HTTP ----------

    private fun get(ctx: Context, query: String, token: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(AuthApi.BASE + "/api.php?action=" + query)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 15000
                doInput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (text.isBlank()) null else JSONObject(text)
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }
}
