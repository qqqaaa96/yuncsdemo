package sy.great.toolbox.tools

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Ed25519 数字签名（纯 Kotlin 实现，无第三方依赖）。
 *
 * 为什么不用 java.security：
 *   · Android 上 Ed25519 支持因厂商而异，不保证存在
 *   · 产物 ELF 里需要独立的验签实现，不能依赖 JCA
 *
 * 实现参考 RFC 8032，使用 BigInteger 做模运算（性能足够，签名只做一次）。
 */
object Ed25519 {

    /** 曲线参数（edwards25519） */
    private val P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val L = BigInteger.TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))
    private val D = BigInteger("-121665")
        .multiply(BigInteger("121666").modInverse(P))
        .mod(P)
    private val I = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)

    /** 基点 B */
    private val Bx = BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202")
    private val By = BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")

    /** 一条椭圆曲线上的点（扩展坐标） */
    private data class Pt(
        val X: BigInteger,
        val Y: BigInteger,
        val Z: BigInteger,
        val T: BigInteger,
    )

    private val IDENTITY = Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private fun ptAdd(p: Pt, q: Pt): Pt {
        val a = p.Y.subtract(p.X).multiply(q.Y.subtract(q.X)).mod(P)
        val b = p.Y.add(p.X).multiply(q.Y.add(q.X)).mod(P)
        val c = p.T.multiply(BigInteger.TWO).multiply(D).multiply(q.T).mod(P)
        val d = p.Z.multiply(BigInteger.TWO).multiply(q.Z).mod(P)
        val e = b.subtract(a)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = b.add(a)
        return Pt(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun ptMul(k: BigInteger, p: Pt): Pt {
        var n = k
        var q = IDENTITY
        var r = p
        while (n > BigInteger.ZERO) {
            if (n.testBit(0)) q = ptAdd(q, r)
            r = ptAdd(r, r)
            n = n.shiftRight(1)
        }
        return q
    }

    /** 缩放到仿射坐标并编码为 32 字节 */
    private fun encode(p: Pt): ByteArray {
        val zInv = p.Z.modInverse(P)
        val x = p.X.multiply(zInv).mod(P)
        val y = p.Y.multiply(zInv).mod(P)
        val out = ByteArray(32)
        val yBytes = toLE(y, 32)
        yBytes.copyInto(out)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    private fun toLE(v: BigInteger, len: Int): ByteArray {
        val out = ByteArray(len)
        val b = v.toByteArray()
        // BigInteger 是大端，可能有符号前导 0
        var src = b
        if (src.size > 1 && src[0] == 0.toByte()) src = src.copyOfRange(1, src.size)
        var i = 0
        while (i < src.size && i < len) {
            out[i] = src[src.size - 1 - i]
            i++
        }
        return out
    }

    private fun fromLE(b: ByteArray): BigInteger {
        val be = ByteArray(b.size + 1)
        for (i in b.indices) be[b.size - 1 - i] = b[i]
        return BigInteger(be)
    }

    private val B: Pt = Pt(Bx, By, BigInteger.ONE, Bx.multiply(By).mod(P))

    /** 32 字节私钥 → 公钥 */
    fun publicKey(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "seed 必须是 32 字节" }
        val h = sha512(seed)
        val a = h.copyOfRange(0, 32)
        a[0] = (a[0].toInt() and 0xF8).toByte()
        a[31] = ((a[31].toInt() and 0x1F) or 0x40).toByte()
        val s = fromLE(a)
        return encode(ptMul(s, B))
    }

    /**
     * 签名。
     *
     * @param message 待签名数据
     * @param seed    32 字节私钥种子
     * @return 64 字节签名（R || S）
     */
    fun sign(message: ByteArray, seed: ByteArray): ByteArray {
        require(seed.size == 32) { "seed 必须是 32 字节" }
        val h = sha512(seed)
        val a = h.copyOfRange(0, 32)
        a[0] = (a[0].toInt() and 0xF8).toByte()
        a[31] = ((a[31].toInt() and 0x1F) or 0x40).toByte()
        val s = fromLE(a)
        val prefix = h.copyOfRange(32, 64)

        val pk = encode(ptMul(s, B))

        // r = SHA512(prefix || M) mod L
        val rInput = prefix + message
        val r = fromLE(sha512(rInput)).mod(L)

        // R = rB
        val R = encode(ptMul(r, B))

        // k = SHA512(R || pk || M) mod L
        val kInput = R + pk + message
        val k = fromLE(sha512(kInput)).mod(L)

        // S = (r + k*s) mod L
        val S = r.add(k.multiply(s)).mod(L)

        return R + toLE(S, 32)
    }

    private fun sha512(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(data)

    /** 生成随机 32 字节种子 */
    fun randomSeed(): ByteArray {
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        return b
    }
}
