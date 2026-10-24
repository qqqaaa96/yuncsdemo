package sy.great.toolbox.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 排行榜 / 签到 / 隐私网络的网络层。
 */
object RankApi {

    // ---------------- 数据结构 ----------------

    data class RankItem(
        val rank: Int,
        val uid: String,
        /** users.id，用于本地头像缓存文件名；0 = 未知 */
        val userId: Int = 0,
        /** -1 表示对方隐矇了积分 */
        val points: Int,
        val name: String,
        /** 头像版本，0 = 无头像 */
        val avatarAt: Int,
        val tag: SyncCenter.Tag?,
        val streak: Int,
        val isMe: Boolean,
    )

    data class RankResult(
        val ok: Boolean,
        val error: String = "",
        val updatedAt: String = "",
        val items: List<RankItem> = emptyList(),
        val myUid: String = "",
    )

    data class CheckinResult(
        val ok: Boolean,
        val error: String = "",
        val gain: Int = 0,
        val streak: Int = 0,
        val total: Int = 0,
        val bonusPct: Int = 0,
        val user: JSONObject? = null,
    )

    // ---------------- 排行榜 ----------------

    fun ranking(ctx: Context): RankResult {
        val token = AuthApi.token(ctx)
        val r = get(ctx, "ranking", token)
            ?: return RankResult(false, "网络错误")

        if (!r.optBoolean("ok", false)) {
            return RankResult(false, r.optString("error", "拉取失败"))
        }

        val data = r.optJSONObject("data")
            ?: return RankResult(false, "返回格式异常")

        val arr = data.optJSONArray("items")
        val items = ArrayList<RankItem>()

        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue

                var tag: SyncCenter.Tag? = null
                val t = o.optJSONObject("tag")
                if (t != null) {
                    val fx = ArrayList<String>()
                    val fa = t.optJSONArray("fxColors")
                    if (fa != null) {
                        for (j in 0 until fa.length()) {
                            val c = fa.optString(j, "")
                            if (c.isNotBlank()) fx.add(c)
                        }
                    }
                    tag = SyncCenter.Tag(
                        text = t.optString("text", ""),
                        color = t.optString("color", ""),
                        fxColors = fx,
                    )
                }

                items.add(
                    RankItem(
                        rank = o.optInt("rank", i + 1),
                        uid = o.optString("uid", ""),
                        userId = o.optInt("userId", 0),
                        points = o.optInt("points", 0),
                        name = o.optString("name", ""),
                        avatarAt = o.optInt("avatar", 0),
                        tag = tag,
                        streak = o.optInt("streak", 0),
                        isMe = o.optBoolean("isMe", false),
                    )
                )
            }
        }

        return RankResult(
            ok = true,
            updatedAt = data.optString("updatedAt", ""),
            items = items,
            myUid = data.optString("myUid", ""),
        )
    }

    // ---------------- 签到 ----------------

    fun checkin(ctx: Context): CheckinResult {
        val token = AuthApi.token(ctx)
        if (token.isEmpty()) return CheckinResult(false, "未登录")

        val r = get(ctx, "checkin", token)
            ?: return CheckinResult(false, "网络错误")

        if (!r.optBoolean("ok", false)) {
            return CheckinResult(false, r.optString("error", "签到失败"))
        }

        val d = r.optJSONObject("data")
            ?: return CheckinResult(false, "返回格式异常")

        return CheckinResult(
            ok = true,
            gain = d.optInt("gain", 0),
            streak = d.optInt("streak", 0),
            total = d.optInt("total", 0),
            bonusPct = d.optInt("bonus", 0),
            user = d.optJSONObject("user"),
        )
    }

    // ---------------- 隐私 ----------------

    /**
     * 保存隐私设置。
     *
     * @param values key 必须是后端白名单内的字段：
     *   showAvatar / showTag / showUid / showEmail / showStreak / showPoints
     */
    fun savePrivacy(ctx: Context, values: Map<String, Boolean>): Boolean {
        val token = AuthApi.token(ctx)
        if (token.isEmpty()) return false

        val qs = buildString {
            append("privacy_save")
            values.forEach { (k, v) ->
                append('&').append(enc(k)).append('=').append(if (v) '1' else '0')
            }
        }

        val r = get(ctx, qs, token) ?: return false
        if (!r.optBoolean("ok", false)) return false

        // 同步到本地用户缓存
        r.optJSONObject("data")?.optJSONObject("user")?.let {
            try { AuthApi.syncUser(ctx, it) } catch (_: Throwable) {}
        }
        return true
    }

    // ---------------- HTTP ----------------

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
                if (token.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
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

    private fun enc(s: String): String =
        try { URLEncoder.encode(s, "UTF-8") } catch (_: Throwable) { s }
}
