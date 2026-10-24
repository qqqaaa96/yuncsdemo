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
        /** 附带积分奖励，0 = 无 */
        val rewardPoints: Int = 0,
        /** 附带经验奖励，0 = 无 */
        val rewardExp: Int = 0,
        /** 是否已领取奖励 */
        val claimed: Boolean = false,
    ) {
        val hasReward: Boolean get() = rewardPoints > 0 || rewardExp > 0
    }

    data class ListResult(
        val ok: Boolean,
        val error: String = "",
        val items: List<Mail> = emptyList(),
        val unread: Int = 0,
    )

    /**
     * 最近一次 list() 的诊断信息。
     *
     * 界面在列表为空时直接展示，方便排查「后台发了但 App 看不到」，
     * 用户不需要去看文件日志。
     */
    @Volatile
    var lastDebug: String = ""
        private set

    // ---------- 列表 ----------

    fun list(ctx: Context, limit: Int = 100): ListResult {
        val token = AuthApi.token(ctx)
        if (token.isEmpty()) {
            return ListResult(false, "未登录")
        }

        val r = get(ctx, "mail_list&limit=$limit", token)
        if (r == null) return ListResult(false, "网络错误")

        // 诊断信息：同时写日志 + 填到 lastDebug 供界面展示
        val dbg = "token=" + token.length + "位(" + token.take(8) + "...)\n" +
                "响应: " + r.toString().take(600)
        lastDebug = dbg
        try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            java.io.File(dir, "mail_debug.log").appendText(
                "\n===== " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()) + " =====\n" + dbg + "\n"
            )
        } catch (_: Throwable) {
        }

        if (!r.optBoolean("ok", false)) {
            return ListResult(false, r.optString("error", "拉取失败"))
        }

        // 兼容多种响应外层结构：
        //   {"ok":true,"data":{"items":[...]}}   ← 当前后端
        //   {"ok":true,"items":[...]}             ← 旧版
        //   {"items":[...]}                        ← 直接返回
        val data = r.optJSONObject("data")
        val arr: org.json.JSONArray? =
            data?.optJSONArray("items")
                ?: r.optJSONArray("items")
                ?: r.optJSONObject("data")?.optJSONArray("list")
                ?: r.optJSONArray("list")

        val items = ArrayList<Mail>()

        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue

                // 兼容 read 字段是 bool 或 0/1
                val readRaw = o.opt("read")
                val isRead = when (readRaw) {
                    is Boolean -> readRaw
                    is Number -> readRaw.toInt() != 0
                    is String -> readRaw == "1" || readRaw.equals("true", true)
                    else -> false
                }

                val emailRaw = o.opt("emailed")
                val isEmailed = when (emailRaw) {
                    is Boolean -> emailRaw
                    is Number -> emailRaw.toInt() != 0
                    else -> false
                }

                items.add(
                    Mail(
                        id = o.optInt("id", 0),
                        title = o.optString("title", ""),
                        body = o.optString("body", ""),
                        level = o.optString("level", "normal").ifBlank { "normal" },
                        sender = o.optString("sender", "系统").ifBlank { "系统" },
                        createdAt = o.optInt("created_at", 0),
                        read = isRead,
                        emailed = isEmailed,
                        rewardPoints = o.optInt("reward_points", 0),
                        rewardExp = o.optInt("reward_exp", 0),
                        claimed = o.optBoolean("claimed", false),
                    )
                )
            }
        }

        val unreadCount = data?.optInt("unread", 0)
            ?: r.optInt("unread", 0)
            ?: items.count { !it.read }

        return ListResult(
            ok = true,
            items = items,
            unread = unreadCount,
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

    /** 领取奖励结果 */
    data class ClaimResult(
        val ok: Boolean,
        val error: String = "",
        val points: Int = 0,
        val exp: Int = 0,
        val level: Int = 1,
        val levelUp: Boolean = false,
    )

    /** 领取邮件奖励 */
    fun claim(ctx: Context, id: Int): ClaimResult {
        val token = AuthApi.token(ctx)
        if (token.isEmpty()) return ClaimResult(false, "未登录")

        val r = get(ctx, "mail_claim&id=$id", token)
            ?: return ClaimResult(false, "网络错误")

        if (!r.optBoolean("ok", false)) {
            return ClaimResult(false, r.optString("error", "领取失败"))
        }

        val d = r.optJSONObject("data")
            ?: return ClaimResult(false, "返回格式异常")

        // 同步用户数据（积分变了）
        d.optJSONObject("user")?.let {
            try { AuthApi.syncUser(ctx, it) } catch (_: Throwable) {}
        }

        return ClaimResult(
            ok = true,
            points = d.optInt("points", 0),
            exp = d.optInt("exp", 0),
            level = d.optInt("level", 1),
            levelUp = d.optBoolean("levelUp", false),
        )
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
