package sy.great.toolbox.tools

/**
 * RC4 加解密（对称，加解密是同一个操作）。
 *
 * 为什么用 RC4 而不是 ChaCha20：
 *   完全按 SSC（ssc-master）的实现来：
 *     · 密钥是 16 位十六进制字符串（如 "a1b2c3d4e5f60718"）
 *     · 每个编译随机生成，用 -DRC4_KEY 注入并用 OBF() 異或混淆
 *     · 用 rc4_skip 支持分段解密
 *
 * RC4 本身强度一般，但：
 *   · 目标是「静态看不到明文」而不是抗密码分析
 *   · 与 SSC 保持一致，行为可预测
 */
object Rc4 {

    /** 生成随机 16 位十六进制密钥（同 SSC 的 --random-key） */
    fun randomKey(): String {
        val bytes = ByteArray(8)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * RC4 加解密。
     *
     * @param data 数据（原地修改不返回新数组，与 SSC 一致）
     * @param key  密钥字符串
     * @param skip 跳过的密钥流字节数（SSC 分段解密用，单段传 0）
     */
    fun apply(data: ByteArray, key: String, skip: Int = 0) {
        val k = key.toByteArray(Charsets.UTF_8)
        if (k.isEmpty()) return

        // KSA
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (k[i % k.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }

        // PRGA
        var i2 = 0
        j = 0

        // 跳过前 skip 个字节
        var remaining = skip
        while (remaining > 0) {
            i2 = (i2 + 1) and 0xFF
            j = (j + s[i2]) and 0xFF
            val t = s[i2]; s[i2] = s[j]; s[j] = t
            remaining--
        }

        for (idx in data.indices) {
            i2 = (i2 + 1) and 0xFF
            j = (j + s[i2]) and 0xFF
            val t = s[i2]; s[i2] = s[j]; s[j] = t
            val ks = s[(s[i2] + s[j]) and 0xFF]
            data[idx] = (data[idx].toInt() xor ks).toByte()
        }
    }
}
