package com.csdemo.tools

import android.util.Base64
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 编解码与哈希。
 */
object Codec {

    fun base64Encode(s: String): String = try {
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    } catch (e: Exception) {
        "编码失败"
    }

    fun base64Decode(s: String): String = try {
        String(Base64.decode(s.trim(), Base64.DEFAULT), Charsets.UTF_8)
    } catch (e: Exception) {
        "解码失败: 不是合法 Base64"
    }

    fun urlEncode(s: String): String = try {
        URLEncoder.encode(s, "UTF-8")
    } catch (e: Exception) {
        "编码失败"
    }

    fun urlDecode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        "解码失败"
    }

    fun hexEncode(s: String): String =
        s.toByteArray(Charsets.UTF_8).joinToString(" ") { "%02X".format(it) }

    fun hexDecode(s: String): String = try {
        val clean = s.replace(Regex("[\\s,:]"), "").replace("0x", "").replace("0X", "")
        val bytes = ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        String(bytes, Charsets.UTF_8)
    } catch (e: Exception) {
        "解码失败: 不是合法十六进制"
    }

    fun htmlEncode(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    fun hash(algo: String, s: String): String = try {
        MessageDigest.getInstance(algo).digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        "计算失败"
    }

    fun allHashes(s: String): List<Pair<String, String>> = listOf(
        "MD5" to hash("MD5", s),
        "SHA-1" to hash("SHA-1", s),
        "SHA-256" to hash("SHA-256", s),
        "SHA-512" to hash("SHA-512", s)
    )

    fun jsonFormat(s: String): String {
        return try {
            val trimmed = s.trim()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return "不是合法 JSON"
            val sb = StringBuilder()
            var indent = 0
            var inStr = false
            var esc = false
            for (c in trimmed) {
                when {
                    esc -> { sb.append(c); esc = false }
                    c == '\\' -> { sb.append(c); esc = true }
                    c == '"' -> { sb.append(c); inStr = !inStr }
                    inStr -> sb.append(c)
                    c == '{' || c == '[' -> { sb.append(c).append('\n'); indent++; pad(sb, indent) }
                    c == '}' || c == ']' -> { sb.append('\n'); indent--; pad(sb, indent); sb.append(c) }
                    c == ',' -> { sb.append(c).append('\n'); pad(sb, indent) }
                    c == ':' -> sb.append(": ")
                    c.isWhitespace() -> {}
                    else -> sb.append(c)
                }
            }
            sb.toString()
        } catch (e: Exception) {
            "格式化失败"
        }
    }

    private fun pad(sb: StringBuilder, n: Int) {
        repeat(n) { sb.append("    ") }
    }
}
