package sy.great.toolbox.tools

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * CQAST7 静态混淆（移植自 ssc_ast3_repl.py 的 build()）。
 *
 * 目标：把纯文本 shell 抬高到「读懂五层变换链」才能还原。
 *
 * 五层（每 32 字节一块）：
 *   1. XOR  —— 块级密钥流 sha256(KEY || 块号)，KEY 32 字节内嵌
 *   2. ROT  —— 每块左旋 r=(块号*7+KEY[0])%7+1 位
 *   3. 半字节交换 —— 相邻 hex 位互换
 *   4. 16 字母随机置换 —— 数据区是大小写字母混排，不是合法 hex
 *   5. 块乱序 —— 文件里的行号与真实块序无关，头部给置换表
 *
 * 产物是一份可执行 sh（含 awk 执行器），与 Python 版逐字节兼容。
 *
 * 严格按 Python 原逻辑实现，不改算法、不改顺序、不改随机分布。
 */
object SscAst7 {

    private const val SEP = "CQ7_zzq2fhvmpwtkx3n"
    private const val MAGIC = "CQAST7"
    private const val DATAEND = "CQDAT_END"
    private const val HEXSET = "0123456789abcdef"
    private const val M64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-"
    private const val CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    private val M64I: Map<Char, Int> = M64.withIndex().associate { (i, c) -> c to i }

    /** 填充大小：用户要求 1.5 MB */
    private const val FILL_BYTES = 1536 * 1024   // 1.5 MB = 1572864

    /**
     * 执行器脚本正文（从 assets 读）。
     *
     * 为什么不嵌在 Kotlin 字符串里：
     *   执行器含 ~100 行 awk，里面有大量 $0 / $i 这类写法，
     *   Kotlin raw string 里必须逐个转义成 ${'$'}{'$'}，极易写错。
     *
     *   放 assets 就跟 monocypher.c 一样，是真实文本，不会走样，
     *   而且能与 Python 版共用同一份来源。
     */
    private const val EXECUTOR_ASSET = "ssc/executor.sh"

    // ============================================================
    // 基础运算
    // ============================================================

    private fun rotl(v: Int, r: Int): Int =
        ((v shl r) or (v shr (8 - r))) and 0xFF

    private fun rotr(v: Int, r: Int): Int =
        ((v shr r) or (v shl (8 - r))) and 0xFF

    private fun sha256(b: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(b)

    /** 块密钥流：sha256(key.hex() + ':' + i) */
    private fun ksOf(key: ByteArray, i: Int): ByteArray {
        val hex = key.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return sha256((hex + ":" + i).toByteArray(Charsets.UTF_8))
    }

    private fun rOf(key: ByteArray, i: Int): Int =
        (i * 7 + (key[0].toInt() and 0xFF)) % 7 + 1

    private fun encBlock(b: ByteArray, key: ByteArray, i: Int): ByteArray {
        val ks = ksOf(key, i)
        val r = rOf(key, i)
        val out = ByteArray(b.size)
        for (j in b.indices) {
            out[j] = rotl((b[j].toInt() and 0xFF) xor (ks[j].toInt() and 0xFF), r).toByte()
        }
        return out
    }

    // ============================================================
    // 16 字母置换
    // ============================================================

    private fun hexToBytes(hex: String): ByteArray {
        val n = hex.length / 2
        val out = ByteArray(n)
        for (i in 0 until n) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) or
                Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    private fun bytesToHex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** hex 字符串 → 字母表映射，再把相邻两位互换 */
    private fun encHex(hexs: String, alpha: String): String {
        val fwd = HashMap<Char, Char>()
        for (i in 0 until 16) fwd[HEXSET[i]] = alpha[i]
        val sb = StringBuilder(hexs.length)
        for (c in hexs) sb.append(fwd[c] ?: c)
        val s = sb.toString()
        val out = StringBuilder(s.length)
        var i = 0
        while (i + 1 < s.length) {
            out.append(s[i + 1]).append(s[i])
            i += 2
        }
        if (i < s.length) out.append(s[i])
        return out.toString()
    }

    // ============================================================
    // 扩展层
    // ============================================================

    private class Cfg(
        val img: String,
        val k1: Int,
        val k2: Int,
        val g: Int,
        val perm: List<Int>,
        val r: Int,
        val t: Int,
        var l1: Int = 0,
    )

    private fun randSample(src: String, n: Int, rnd: SecureRandom): String {
        val list = src.toMutableList()
        val out = StringBuilder(n)
        for (i in 0 until n) {
            val idx = rnd.nextInt(list.size)
            out.append(list.removeAt(idx))
        }
        return out.toString()
    }

    private fun makeCfg(rnd: SecureRandom): Cfg {
        val img = randSample(M64, 16, rnd)
        val g = 8
        val perm = (0 until g).toMutableList()
        java.util.Collections.shuffle(perm, rnd)
        return Cfg(
            img = img,
            k1 = 3 + rnd.nextInt(59),      // randint(3,61)
            k2 = rnd.nextInt(64),          // randint(0,63)
            g = g,
            perm = perm,
            r = 6 + rnd.nextInt(19),       // randint(6,24)
            t = 6 + rnd.nextInt(19),       // randint(6,24)
        )
    }

    private fun junk(n: Int, rnd: SecureRandom): String {
        val sb = StringBuilder(n)
        repeat(n) { sb.append(M64[rnd.nextInt(64)]) }
        return sb.toString()
    }

    /** N1 表映射 → N2 位置位移 → N3 段置换 → N5 每5插1 → N4 每1插1 → N6 前后缀 */
    private fun ePack(stream: String, alpha: String, cfg: Cfg, rnd: SecureRandom): String {
        // N1：字母表 → img
        val s1 = StringBuilder(stream.length)
        for (c in stream) s1.append(cfg.img[alpha.indexOf(c)])

        // N2：位置位移
        val s2 = StringBuilder(s1.length)
        for (i in s1.indices) {
            val v = (M64I[s1[i]]!! + i * cfg.k1 + cfg.k2) % 64
            s2.append(M64[v])
        }

        // N3：分段置换
        val s2s = s2.toString()
        val l = s2s.length
        val g = cfg.g
        val q = l / g
        val rem = l % g
        val segs = ArrayList<String>(g)
        var pos = 0
        for (i in 0 until g) {
            val n = q + if (i < rem) 1 else 0
            segs.add(s2s.substring(pos, pos + n))
            pos += n
        }
        val s3 = StringBuilder(l)
        for (i in 0 until g) s3.append(segs[cfg.perm[i]])

        // N5：每 5 个插 1
        val s5 = StringBuilder(l + l / 5 + 1)
        var cnt = 0
        for (c in s3) {
            s5.append(c)
            cnt++
            if (cnt == 5) {
                s5.append(junk(1, rnd))
                cnt = 0
            }
        }

        // N4：每 1 插 1
        val s4 = StringBuilder(s5.length * 2)
        for (c in s5) {
            s4.append(c).append(junk(1, rnd))
        }

        // N6：前后缀
        return junk(cfg.r, rnd) + s4.toString() + junk(cfg.t, rnd)
    }

    // ============================================================
    // 主入口
    // ============================================================

    data class Result(val text: String, val plainLen: Int, val blockCount: Int)

    /**
     * 打包：把明文脚本变成 CQAST7 加固脚本。
     *
     * @param raw 原脚本字节
     * @param executorBody 执行器脚本正文（与 Python 版 EXECUTOR_BODY 一致）
     */
    /** 从 assets 读执行器正文（ssc/executor.sh） */
    fun loadExecutor(ctx: android.content.Context): String? = try {
        ctx.assets.open(EXECUTOR_ASSET).use { String(it.readBytes(), Charsets.UTF_8) }
    } catch (e: Throwable) {
        null
    }

    fun pack(raw: ByteArray, executorBody: String): Result {
        val rnd = SecureRandom()
        val key = ByteArray(32)
        rnd.nextBytes(key)
        val alpha = randSample(CHARSET, 16, rnd)

        // 补齐到 32 字节
        val pad = (32 - raw.size % 32) % 32
        val plain = ByteArray(raw.size + pad)
        raw.copyInto(plain, 0)
        if (pad > 0) {
            val padBytes = ByteArray(pad)
            rnd.nextBytes(padBytes)
            padBytes.copyInto(plain, raw.size)
        }

        val n = plain.size / 32
        val blocks = ArrayList<ByteArray>(n)
        for (i in 0 until n) {
            blocks.add(encBlock(plain.copyOfRange(i * 32, (i + 1) * 32), key, i))
        }

        // 块乱序
        val perm = (0 until n).toMutableList()
        java.util.Collections.shuffle(perm, rnd)

        val rows = ArrayList<String>(n)
        for (j in 0 until n) {
            rows.add(encHex(bytesToHex(blocks[perm[j]]), alpha))
        }

        val want = bytesToHex(sha256(rows.joinToString("").toByteArray(Charsets.UTF_8)))

        // 头部
        val h2 = ByteArray(8)
        writeBE32(h2, 0, plain.size)
        writeBE32(h2, 4, raw.size)

        val permS = perm.joinToString(",") { (it + 1).toString() }

        // 填充行（1.5MB）
        val fillRows = ArrayList<String>()
        val fillBytes = FILL_BYTES
        val per = 64
        val cnt = fillBytes / per
        for (i in 0 until cnt) {
            val sb = StringBuilder(per)
            repeat(per) { sb.append(HEXSET[rnd.nextInt(16)]) }
            fillRows.add(sb.toString())
        }

        val cfg = makeCfg(rnd)
        val stream = rows.joinToString("")
        cfg.l1 = stream.length
        val blob = ePack(stream, alpha, cfg, rnd)

        // 按 120 字符切行
        val lines = ArrayList<String>()
        var i = 0
        while (i < blob.length) {
            lines.add(blob.substring(i, minOf(i + 120, blob.length)))
            i += 120
        }

        val extl = listOf(cfg.img, cfg.k1, cfg.k2, cfg.g, cfg.r, cfg.t, cfg.l1)
            .joinToString(",")
        val perml = cfg.perm.joinToString(",")

        val sb = StringBuilder()
        sb.append("#!/system/bin/sh\n")
        sb.append("# 系统组件\n")
        sb.append("set -u\n")
        sb.append("S=$(cat <<'CQEXEC_EOF'\n")
        // Python 那边 EXECUTOR_BODY 是 rstrip('\n') 过的，
        // assets 文件可能带回车/末尾换行，这里统一去掉，保证与 Python 一致。
        sb.append(executorBody.trimEnd('\n', '\r'))
        sb.append("\nCQEXEC_EOF\n")
        sb.append(")\n")
        sb.append("sh -c \"\$S\" cqex \"\$@\" 3<<'").append(SEP).append("'\n")
        sb.append(MAGIC).append('\n')
        // h1 = 'CQAS' + [7,32,0,0] + BE32(len(plain))
        sb.append("43514153").append("07200000").append(be32Hex(plain.size)).append('\n')
        sb.append(bytesToHex(h2)).append('\n')
        sb.append(alpha).append('\n')
        sb.append(bytesToHex(key)).append('\n')
        sb.append(permS).append('\n')
        sb.append(want).append('\n')
        sb.append(extl).append('\n')
        sb.append(perml).append('\n')
        for (l in lines) sb.append(l).append('\n')
        sb.append(DATAEND).append('\n')
        for (r in fillRows) sb.append(r).append('\n')
        sb.append(SEP).append('\n')
        sb.append("exit \$?\n")

        return Result(sb.toString(), raw.size, n)
    }

    private fun writeBE32(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 24) and 0xFF).toByte()
        b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }

    private fun be32Hex(v: Int): String =
        "%08x".format(v)
}
