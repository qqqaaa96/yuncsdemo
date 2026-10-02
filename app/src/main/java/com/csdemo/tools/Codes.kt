package com.csdemo.tools

/**
 * 验证码相关工具。
 *
 * 包含两类：
 *   1) 图形验证码文本（4 位，防机器人）
 *   2) 邮箱验证码（6 位数字）
 */
object Codes {

    private const val GRAPHIC_CHARS = "ABCDEFGHJKLMNPQRSTUVWXY23456789"

    /** 生成 4 位图形验证码（去掉易混淆字符） */
    fun graphic(len: Int = 4): String {
        val sb = StringBuilder(len)
        val r = java.util.Random(System.nanoTime())
        repeat(len) {
            sb.append(GRAPHIC_CHARS[r.nextInt(GRAPHIC_CHARS.length)])
        }
        return sb.toString()
    }

    /** 生成 6 位数字邮件验证码 */
    fun mail(len: Int = 6): String {
        val sb = StringBuilder(len)
        val r = java.security.SecureRandom()
        repeat(len) {
            sb.append(r.nextInt(10))
        }
        // 避免以 0 开头看起来像 5 位
        if (sb[0] == '0') sb.setCharAt(0, ('1' + r.nextInt(9)).toChar())
        return sb.toString()
    }

    /** 不区分大小写比较图形验证码 */
    fun matchGraphic(input: String, target: String): Boolean =
        input.trim().equals(target.trim(), ignoreCase = true)

    /** 邮件验证码比较 */
    fun matchMail(input: String, target: String): Boolean =
        input.trim() == target.trim()
}
