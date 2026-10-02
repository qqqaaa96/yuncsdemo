package com.csdemo.tools

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * 邮箱验证会话。
 *
 * 职责：
 *   1. 保存发件邮箱与授权码（用于 SMTP）
 *   2. 发放验证码（发送成功时暂存）
 *   3. 校验用户输入的验证码
 *
 * ⚠ 安全提醒：
 *   授权码写在本文件内。若不加固，反编译即可读到。
 *   请确保发布前已做代码保护，并定期更换授权码。
 */
object MailAuth {

    // ============================================================
    // 发件配置
    // ============================================================

    /** 发件邮箱（必须是开启 SMTP 的那个邮箱） */
    const val SENDER = "1563988904@qq.com"

    /** 发件人显示名 */
    const val SENDER_NAME = "工具箱"

    /** 邮件主题 */
    const val SUBJECT = "验证码"

    /**
     * SMTP 授权码。
     *
     * 注意：这是邮箱的授权码，不是登录密码。
     * 建议发布前替换为新的授权码，并配合代码保护措施。
     */
    const val AUTH_CODE = "uvwilgvmphiojbbc"

    /** 邮件正文 */
    fun bodyFor(code: String): String {
        return "您的验证码是：" + code + "\n\n" +
                "请在应用内输入该验证码完成验证。\n" +
                "验证码 10 分钟内有效，请勿泄露给他人。\n\n" +
                "若非本人操作，请忽略本邮件。"
    }

    // ============================================================
    // 会话状态
    // ============================================================

    /** 本次会话是否已通过验证 */
    val verified = mutableStateOf(false)

    /** 待校验的验证码 */
    @Volatile
    private var pendingCode: String = ""

    /** 验证码对应的邮箱 */
    @Volatile
    private var pendingEmail: String = ""

    /** 发放时间，用于超时 */
    @Volatile
    private var issuedAt: Long = 0L

    /** 有效期：10 分钟 */
    private const val TTL_MS = 10 * 60 * 1000L

    /** 重发冷却：60 秒 */
    private const val RESEND_COOLDOWN_MS = 60 * 1000L

    private var lastSentAt: Long = 0L

    /** 持久化存储：记住已验证状态与邮箱 */
    private const val PREFS = "csdemo_mailverify"
    private const val K_PASSED = "passed"
    private const val K_EMAIL = "email"

    /**
     * 加载。
     *
     * 只要之前验证通过过，就直接恢复为已通过，
     * 后续启动不再弹出验证页面。
     */
    fun load(ctx: Context) {
        val sp = try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            null
        }
        verified.value = sp?.getBoolean(K_PASSED, false) ?: false
        lastEmail = sp?.getString(K_EMAIL, "") ?: ""
        // 临时验证码不持久化
        pendingCode = ""
        pendingEmail = ""
        issuedAt = 0L
        lastSentAt = 0L
    }

    /** 上次验证通过时使用的邮箱 */
    @Volatile
    private var lastEmail: String = ""

    fun lastUsedEmail(): String = lastEmail

    /** 手动清除验证状态（用于调试或重新验证） */
    fun clear(ctx: Context) {
        verified.value = false
        lastEmail = ""
        pendingCode = ""
        pendingEmail = ""
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (e: Exception) {
        }
    }

    /** 发放验证码 */
    fun issue(email: String, code: String) {
        pendingEmail = email.trim()
        pendingCode = code.trim()
        issuedAt = System.currentTimeMillis()
        lastSentAt = issuedAt
    }

    /** 目标邮箱 */
    fun targetEmail(): String = pendingEmail

    /** 是否还在重发冷却中 */
    fun inCooldown(): Boolean =
        System.currentTimeMillis() - lastSentAt < RESEND_COOLDOWN_MS

    /** 剩余冷却秒数 */
    fun cooldownSeconds(): Int {
        val left = RESEND_COOLDOWN_MS - (System.currentTimeMillis() - lastSentAt)
        return if (left <= 0) 0 else (left / 1000).toInt() + 1
    }

    /** 是否有待校验的验证码 */
    fun hasPending(): Boolean = pendingCode.isNotEmpty()

    /** 校验验证码 */
    fun check(input: String): CheckResult {
        val v = input.trim()
        if (pendingCode.isEmpty()) {
            return CheckResult(false, "验证码尚未发送，请先获取验证码")
        }
        if (System.currentTimeMillis() - issuedAt > TTL_MS) {
            return CheckResult(false, "验证码已过期，请重新发送")
        }
        if (v.length != pendingCode.length) {
            return CheckResult(false, "请输入完整的 " + pendingCode.length + " 位验证码")
        }
        if (v != pendingCode) {
            return CheckResult(false, "验证码不正确")
        }
        return CheckResult(true, "")
    }

    /**
     * 标记通过，并持久化。
     * 只要通过一次，下次启动不再要求验证。
     */
    fun markPassed(ctx: Context) {
        verified.value = true
        lastEmail = pendingEmail
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(K_PASSED, true)
                .putString(K_EMAIL, lastEmail)
                .apply()
        } catch (e: Exception) {
        }
        pendingCode = ""
    }

    data class CheckResult(val ok: Boolean, val message: String)
}
