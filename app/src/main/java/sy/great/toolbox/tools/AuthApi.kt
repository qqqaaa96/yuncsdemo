package sy.great.toolbox.tools

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 登录系统网络层。
 *
 * 服务端地址来自 frpc.ini：
 *   localPort 8080 → remotePort 65535 @ lsj.2.frp.one
 *   即 http://lsj.2.frp.one:65535/api.php
 *
 * token 存 SharedPreferences，同时也可以作为 Cookie（服务端会下发）。
 */
object AuthApi {

    /**
     * 服务端基础地址（末尾不带斜杠）。
     *
     * chmlfrp 隧道类型为 HTTP：
     *   边缘**不解密 TLS**，只做四层转发，
     *   而本地是 `php -S`（不支持 TLS），
     *   所以必须走明文 http。
     *
     * 已确认可用：
     *   http://denglu.fucku.top/api.php?action=ping
     *   → {"ok":true,...,"captcha":"on"}
     *
     * 如果以后换成能在边缘解密的服务（或本地上了 TLS 终结），
     * 这里改成 https:// 即可，其余代码不用动。
     */
    const val BASE = "http://denglu.fucku.top"

    private const val PREFS = "csdemo_auth"
    private const val K_TOKEN = "token"
    private const val K_USERNAME = "username"
    private const val K_EMAIL = "email"
    private const val K_UID = "uid"

    data class Resp(
        val ok: Boolean,
        val error: String = "",
        val code: String = "",
        val json: JSONObject? = null
    ) {
        fun str(key: String): String = json?.optString(key, "") ?: ""
        fun int(key: String): Int = json?.optInt(key, 0) ?: 0
        fun obj(key: String): JSONObject? = json?.optJSONObject(key)
    }

    // ---------- token 存储 ----------

    fun saveToken(ctx: Context, token: String, username: String, email: String, uid: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_TOKEN, token)
            .putString(K_USERNAME, username)
            .putString(K_EMAIL, email)
            .putInt(K_UID, uid)
            .apply()
    }

    fun token(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_TOKEN, "") ?: ""

    fun username(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_USERNAME, "") ?: ""

    fun email(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_EMAIL, "") ?: ""

    fun clearToken(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** 本地是否有 token（不代表服务端仍有效） */
    fun hasToken(ctx: Context): Boolean = token(ctx).isNotEmpty()

    // ---------- HTTP ----------

    private fun request(
        action: String,
        body: JSONObject? = null,
        useToken: String = "",
        timeoutMs: Int = 20000
    ): Resp {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$BASE/api.php?action=$action")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = if (body == null) "GET" else "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doInput = true
                setRequestProperty("Accept", "application/json")
                if (useToken.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $useToken")
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }

            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }

            val httpCode = conn.responseCode
            val stream = if (httpCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

            val json = try {
                if (text.isNotBlank()) JSONObject(text) else null
            } catch (_: Throwable) {
                null
            }

            if (json == null) {
                Resp(false, "服务器返回异常（HTTP $httpCode）")
            } else {
                val okFlag = json.optBoolean("ok", false)
                Resp(
                    ok = okFlag,
                    error = json.optString("error", ""),
                    code = json.optString("code", ""),
                    json = json
                )
            }
        } catch (e: Throwable) {
            Resp(false, "网络错误：" + (e.message ?: e.javaClass.simpleName))
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    // ---------- 接口 ----------

    /** 健康检查 */
    fun ping(): Resp = request("ping")

    /**
     * 图形验证码结果：图片字节 + 本次的 captcha_id
     */
    data class Captcha(val bytes: ByteArray, val id: String, val error: String = "") {
        val ok: Boolean get() = bytes.isNotEmpty() && id.isNotEmpty() && error.isEmpty()
    }

    /**
     * 拉取图形验证码。
     *
     * 服务端返回 PNG，captcha_id 在响应头 X-Captcha-Id。
     */
    fun captcha(): Captcha {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$BASE/api.php?action=captcha")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                doInput = true
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return Captcha(ByteArray(0), "", "验证码加载失败（HTTP $code）")
            }
            val id = conn.getHeaderField("X-Captcha-Id") ?: ""
            val bytes = conn.inputStream.use { it.readBytes() }
            if (id.isEmpty()) {
                return Captcha(ByteArray(0), "", "服务器未返回 captcha_id")
            }
            if (bytes.isEmpty()) {
                return Captcha(ByteArray(0), "", "验证码图片为空")
            }
            Captcha(bytes, id)
        } catch (e: Throwable) {
            Captcha(ByteArray(0), "", "网络错误：" + (e.message ?: e.javaClass.simpleName))
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /** 发送验证码（需过图形验证码） */
    fun sendCode(
        email: String,
        purpose: String = "register",
        captchaId: String = "",
        captchaCode: String = ""
    ): Resp {
        val b = JSONObject()
        b.put("email", email)
        b.put("purpose", purpose)
        b.put("captcha_id", captchaId)
        b.put("captcha_code", captchaCode)
        return request("send_code", b)
    }

    /** 注册（带设备指纹） */
    fun register(
        username: String,
        email: String,
        password: String,
        code: String,
        deviceId: String = ""
    ): Resp {
        val b = JSONObject()
        b.put("username", username)
        b.put("email", email)
        b.put("password", password)
        b.put("code", code)
        b.put("device_id", deviceId)
        return request("register", b)
    }

    /** 登录（用户名或邮箱） */
    fun login(account: String, password: String): Resp {
        val b = JSONObject()
        b.put("username", account)
        b.put("password", password)
        return request("login", b)
    }

    fun logout(ctx: Context): Resp {
        val t = token(ctx)
        val r = request("logout", JSONObject(), t)
        clearToken(ctx)
        return r
    }

    /** 当前用户（验证 token 是否还有效） */
    fun me(ctx: Context): Resp = request("me", null, token(ctx))

    fun changePassword(ctx: Context, oldPwd: String, newPwd: String): Resp {
        val b = JSONObject()
        b.put("old_password", oldPwd)
        b.put("new_password", newPwd)
        return request("change_password", b, token(ctx))
    }

    fun resetSend(
        email: String,
        captchaId: String = "",
        captchaCode: String = ""
    ): Resp {
        val b = JSONObject()
        b.put("email", email)
        b.put("captcha_id", captchaId)
        b.put("captcha_code", captchaCode)
        return request("reset_send", b)
    }

    fun resetPassword(email: String, code: String, newPwd: String): Resp {
        val b = JSONObject()
        b.put("email", email)
        b.put("code", code)
        b.put("new_password", newPwd)
        return request("reset_password", b)
    }

    /** 把注册/登录返回的 token 与用户信息存到本地 */
    fun persistLogin(ctx: Context, r: Resp): Boolean {
        if (!r.ok) return false
        val t = r.str("token")
        if (t.isEmpty()) return false
        val u = r.obj("user") ?: return false
        saveToken(
            ctx,
            t,
            u.optString("username", ""),
            u.optString("email", ""),
            u.optInt("id", 0)
        )
        return true
    }
}
