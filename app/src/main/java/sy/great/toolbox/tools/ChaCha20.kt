package sy.great.toolbox.tools

import java.security.SecureRandom

/**
 * ChaCha20 流加密（纯 Kotlin 实现，无第三方依赖）。
 *
 * 为什么用 ChaCha20 而不是 AES：
 *   · 不需要填充，流式加密任意长度
 *   · 纯整数运算，实现简单，无查表泄露风险
 *   · 相同代码可以直接翻译成 C，嵌到生成的 ELF 里
 *
 * 每次加密用全新的随机 key（32 字节）和 nonce（12 字节），
 * 两者都会作为常量写进 ELF，运行时用于解密。
 */
object ChaCha20 {

    data class Cipher(
        val data: ByteArray,
        val key: ByteArray,
        val nonce: ByteArray,
    )

    /** 加密：生成随机 key/nonce，返回密文 */
    fun encrypt(plain: ByteArray): Cipher {
        val key = ByteArray(32)
        val nonce = ByteArray(12)
        val rnd = SecureRandom()
        rnd.nextBytes(key)
        rnd.nextBytes(nonce)
        return Cipher(apply(plain, key, nonce), key, nonce)
    }

    /**
     * ChaCha20 加/解密（同一个操作）。
     *
     * 初始计数器为 1（0 号块保留给 Poly1305，这里不用）。
     */
    fun apply(input: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == 32) { "key 必须是 32 字节" }
        require(nonce.size == 12) { "nonce 必须是 12 字节" }

        val out = ByteArray(input.size)
        val state = IntArray(16)

        // 常量 "expand 32-byte k"
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574

        // key（32 字节 = 8 个 int，小端）
        for (i in 0 until 8) {
            state[4 + i] = le32(key, i * 4)
        }

        // counter（1 个 int）+ nonce（3 个 int）
        var counter = 1

        var offset = 0
        val block = IntArray(16)
        val keystream = ByteArray(64)

        while (offset < input.size) {
            state[12] = counter
            state[13] = le32(nonce, 0)
            state[14] = le32(nonce, 4)
            state[15] = le32(nonce, 8)

            chachaBlock(state, block)

            // block → 64 字节小端
            for (i in 0 until 16) {
                val v = block[i]
                val p = i * 4
                keystream[p] = (v and 0xFF).toByte()
                keystream[p + 1] = ((v ushr 8) and 0xFF).toByte()
                keystream[p + 2] = ((v ushr 16) and 0xFF).toByte()
                keystream[p + 3] = ((v ushr 24) and 0xFF).toByte()
            }

            val n = minOf(64, input.size - offset)
            for (i in 0 until n) {
                out[offset + i] = (input[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }

            offset += n
            counter++
        }

        return out
    }

    /** 读 4 字节小端 */
    private fun le32(b: ByteArray, off: Int): Int {
        return (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
    }

    /** ChaCha20 20 轮（10 次双轮） */
    private fun chachaBlock(input: IntArray, out: IntArray) {
        val x = IntArray(16)
        System.arraycopy(input, 0, x, 0, 16)

        repeat(10) {
            // 列轮
            quarterRound(x, 0, 4, 8, 12)
            quarterRound(x, 1, 5, 9, 13)
            quarterRound(x, 2, 6, 10, 14)
            quarterRound(x, 3, 7, 11, 15)
            // 对角轮
            quarterRound(x, 0, 5, 10, 15)
            quarterRound(x, 1, 6, 11, 12)
            quarterRound(x, 2, 7, 8, 13)
            quarterRound(x, 3, 4, 9, 14)
        }

        for (i in 0 until 16) {
            out[i] = x[i] + input[i]
        }
    }

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
    }
}
