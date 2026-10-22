package sy.great.toolbox.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 已登录过的账号记录。
 *
 * 用途：
 *   退出登录时不删 token，只把它移入历史；
 *   下次在「切换账号」里点一下就能直接登录，不用重新输密码。
 *
 * 安全边界：
 *   · token 本来就存在本应用私有目录（SharedPreferences），
 *     存多份不降低安全级别。
 *   · 服务端 token 有 30 天有效期，过期后自动失效，
 *     届时点击会提示「已失效」并从列表移除。
 *   · 删除账号 / 手动「退出全部」时清空整个历史。
 *
 * 存储格式（SharedPreferences 里一段 JSON 数组）：
 *   [ { "uid":"00000", "username":"...", "email":"...",
 *       "token":"...", "avatarAt":3, "savedAt":1699... } ]
 */
object AccountStore {

    private const val PREFS = "csdemo_accounts"
    private const val K_LIST = "list"

    /** 最多保留多少个账号（超出丢弃最旧的） */
    private const val MAX_ACCOUNTS = 5

    data class Account(
        val uid0: String,
        val username: String,
        val email: String,
        val token: String,
        val avatarAt: Int,
        val savedAt: Long,
    ) {
        /** 列表里显示的名字：优先用户名 */
        val label: String get() = username.ifBlank { email.ifBlank { uid0 } }
    }

    // ---------- 读 ----------

    fun list(ctx: Context): List<Account> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_LIST, "") ?: ""
        if (raw.isBlank()) return emptyList()

        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Account>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val token = o.optString("token", "")
                if (token.isBlank()) continue
                out.add(
                    Account(
                        uid0 = o.optString("uid", ""),
                        username = o.optString("username", ""),
                        email = o.optString("email", ""),
                        token = token,
                        avatarAt = o.optInt("avatarAt", 0),
                        savedAt = o.optLong("savedAt", 0L),
                    )
                )
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 按 uid0 找 */
    fun find(ctx: Context, uid0: String): Account? =
        list(ctx).firstOrNull { it.uid0 == uid0 }

    // ---------- 写 ----------

    private fun save(ctx: Context, items: List<Account>) {
        val arr = JSONArray()
        items.take(MAX_ACCOUNTS).forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("uid", a.uid0)
                    put("username", a.username)
                    put("email", a.email)
                    put("token", a.token)
                    put("avatarAt", a.avatarAt)
                    put("savedAt", a.savedAt)
                }
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_LIST, arr.toString())
            .apply()
    }

    /**
     * 记下当前登录的账号（登录成功后调）。
     *
     * 同一 uid0 会被覆盖，时间刷新。
     */
    fun remember(ctx: Context) {
        val token = AuthApi.token(ctx)
        if (token.isBlank()) return

        val uid0 = AuthApi.uid0(ctx)
        val me = Account(
            uid0 = uid0,
            username = AuthApi.username(ctx),
            email = AuthApi.email(ctx),
            token = token,
            avatarAt = AuthApi.avatarAt(ctx),
            savedAt = System.currentTimeMillis(),
        )

        val rest = list(ctx).filterNot { it.uid0 == uid0 && uid0.isNotBlank() }
        save(ctx, (listOf(me) + rest).take(MAX_ACCOUNTS))
    }

    /** 移除某个账号 */
    fun remove(ctx: Context, uid0: String) {
        save(ctx, list(ctx).filterNot { it.uid0 == uid0 })
    }

    /** 清空（删除账号 / 退出全部时用） */
    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(K_LIST)
            .apply()
    }
}
