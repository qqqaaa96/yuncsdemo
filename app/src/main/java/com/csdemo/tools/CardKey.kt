package com.csdemo.tools

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * 卡密验证。
 *
 * 6 位卡密，默认可用值 123456。
 * 验证通过后写入本地，再次启动直接进入。
 */
object CardKey {

    private const val PREFS = "csdemo_cardkey"
    private const val K_VERIFIED = "verified"
    private const val K_USED_CODE = "used_code"

    /** 长度 */
    const val LEN = 6

    /** 默认卡密 */
    const val DEFAULT_CODE = "123456"

    /** 可供验证的卡密集合（可扩展为多卡密列表） */
    private val VALID_CODES = setOf(
        DEFAULT_CODE
    )

    /**
     * Compose 可观察状态。
     *
     * 注意：本状态只在单次进程生命周期内有效。
     * 每次冷启动都会重置为 false，因为要求“每次进入应用都要输入卡密”。
     */
    val verified = mutableStateOf(false)

    /**
     * 加载。
     *
     * 需求是每次启动都必须验证，所以这里不恢复历史验证状态，
     * 而是主动清掉标记，保证冷启动后一定回到验证页。
     */
    fun load(ctx: Context) {
        verified.value = false
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(K_VERIFIED, false).apply()
        } catch (e: Exception) {
        }
    }

    /** 验证卡密 */
    fun verify(code: String): Boolean {
        val c = code.trim()
        if (c.length != LEN) return false
        return VALID_CODES.contains(c)
    }

    /**
     * 标记本次会话已通过。
     * 只改内存状态，不持久化，以便下次启动仍然需要验证。
     */
    fun markVerified(ctx: Context, code: String) {
        verified.value = true
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(K_VERIFIED, false)
                .putString(K_USED_CODE, code.trim())
                .apply()
        } catch (e: Exception) {
        }
    }

    /** 清除（用于调试或重置） */
    fun reset(ctx: Context) {
        verified.value = false
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun usedCode(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_USED_CODE, "") ?: ""
}
