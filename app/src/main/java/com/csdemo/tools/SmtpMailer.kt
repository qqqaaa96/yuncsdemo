package com.csdemo.tools

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import javax.net.ssl.SSLSocketFactory

/**
 * 极简 SMTP over SSL 客户端（不依赖任何第三方库）。
 *
 * 目标场景：QQ 邮箱通过 SMTP 发送验证码。
 * QQ 要求：
 *   · 服务器 smtp.qq.com
 *   · 端口 465（SMTPS，直接 SSL），或 587（STARTTLS）
 *   · 认证使用“授权码”，不是登录密码
 *   · 发件人必须等于登录账号
 *
 * 实现流程（RFC 5321 子集）：
 *   EHLO → AUTH LOGIN → MAIL FROM → RCPT TO → DATA → QUIT
 *
 * 说明：这是最小实现，只处理必要的响应码，不做 MIME 附件、
 * 不处理多收件人分批、不做 DKIM 签名。
 */
object SmtpMailer {

    data class Config(
        val host: String = "smtp.qq.com",
        val port: Int = 465,
        val user: String,          // 完整邮箱地址
        val authCode: String,      // 授权码
        val fromName: String = ""
    )

    data class Result(
        val ok: Boolean,
        val message: String,
        val log: String = ""
    )

    /**
     * 发送纯文本邮件（直接 SSL，适合 465 端口）。
     *
     * @param timeoutMs 连接与读写超时
     */
    fun send(
        cfg: Config,
        to: String,
        subject: String,
        body: String,
        timeoutMs: Int = 20000
    ): Result {
        val log = StringBuilder()
        var socket: Socket? = null
        return try {
            // 1. 参数校验
            validate(cfg, to)?.let { return it }

            // 2. 建立 SSL 连接
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val s = factory.createSocket() as Socket
            s.connect(InetSocketAddress(cfg.host, cfg.port), timeoutMs)
            s.soTimeout = timeoutMs
            socket = s

            val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.ISO_8859_1))
            val writer = BufferedWriter(OutputStreamWriter(s.outputStream, Charsets.ISO_8859_1))

            fun readLine(): String {
                val line = reader.readLine().orEmpty()
                log.append("S: ").append(line).append('\n')
                return line
            }

            fun send(cmd: String, hide: Boolean = false) {
                log.append("C: ").append(if (hide) "******" else cmd).append('\n')
                writer.write(cmd + "\r\n")
                writer.flush()
            }

            // 3. 读问候
            val greeting = readLine()
            if (!greeting.startsWith("220")) {
                return Result(false, "服务器未就绪：$greeting", log.toString())
            }

            // 4. EHLO
            send("EHLO csdemo")
            while (true) {
                val l = readLine()
                if (l.length < 4 || l[3] != '-') break
            }

            // 5. AUTH LOGIN
            send("AUTH LOGIN")
            val authReq = readLine()
            if (!authReq.startsWith("334")) {
                return Result(false, "服务器不支持 AUTH LOGIN：$authReq", log.toString())
            }

            send(b64(cfg.user), hide = true)
            val userResp = readLine()
            if (!userResp.startsWith("334")) {
                return Result(false, "账号被拒：$userResp", log.toString())
            }

            send(b64(cfg.authCode), hide = true)
            val passResp = readLine()
            if (!passResp.startsWith("235")) {
                return Result(
                    false,
                    "认证失败（" + passResp + "）。请确认已开启 SMTP 服务且使用的是授权码，" +
                            "而非登录密码；部分网络环境下 QQ 会限制非常用 IP 登录。",
                    log.toString()
                )
            }

            // 6. MAIL FROM
            send("MAIL FROM:<" + cfg.user + ">")
            val mf = readLine()
            if (!mf.startsWith("250")) {
                return Result(false, "MAIL FROM 被拒：$mf", log.toString())
            }

            // 7. RCPT TO
            send("RCPT TO:<" + to + ">")
            val rc = readLine()
            if (!rc.startsWith("250") && !rc.startsWith("251")) {
                return Result(false, "收件人被拒：$rc", log.toString())
            }

            // 8. DATA
            send("DATA")
            val data = readLine()
            if (!data.startsWith("354")) {
                return Result(false, "DATA 被拒：$data", log.toString())
            }

            // 9. 邮件头 + 正文
            val fromHeader = if (cfg.fromName.isBlank()) cfg.user
            else "=?UTF-8?B?" + b64(cfg.fromName) + "?= <" + cfg.user + ">"
            val sb = StringBuilder()
            sb.append("From: ").append(fromHeader).append("\r\n")
            sb.append("To: <").append(to).append(">\r\n")
            sb.append("Subject: =?UTF-8?B?").append(b64(subject)).append("?=\r\n")
            sb.append("MIME-Version: 1.0\r\n")
            sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Date: ").append(rfcDate()).append("\r\n")
            sb.append("\r\n")
            // 正文 base64，每行 76 字符
            val bodyB64 = b64(body)
            var i = 0
            while (i < bodyB64.length) {
                val end = minOf(i + 76, bodyB64.length)
                sb.append(bodyB64, i, end).append("\r\n")
                i = end
            }
            if (!bodyB64.endsWith("\n")) sb.append("\r\n")
            sb.append(".\r\n")

            writer.write(sb.toString())
            writer.flush()

            val sent = readLine()
            if (!sent.startsWith("250")) {
                return Result(false, "发送失败：$sent", log.toString())
            }

            // 10. QUIT
            try {
                send("QUIT")
                readLine()
            } catch (_: Exception) {
            }

            Result(true, "邮件已发送至 " + to, log.toString())
        } catch (e: javax.net.ssl.SSLException) {
            Result(false, "SSL 错误：" + (e.message ?: "未知") +
                    "。可能是网络阻断或端口被运营商限制，可尝试 587 端口。", log.toString())
        } catch (e: java.net.SocketTimeoutException) {
            Result(false, "连接超时。请检查网络，或尝试 587 端口。", log.toString())
        } catch (e: Exception) {
            Result(false, "发送异常：" + (e.message ?: e.javaClass.simpleName), log.toString())
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    /** 基本参数校验 */
    private fun validate(cfg: Config, to: String): Result? {
        if (cfg.user.isBlank()) return Result(false, "未配置发件邮箱")
        if (cfg.authCode.isBlank()) return Result(false, "未配置授权码")
        if (!isEmail(cfg.user)) return Result(false, "发件邮箱格式不正确")
        if (!isEmail(to)) return Result(false, "收件邮箱格式不正确")
        return null
    }

    fun isEmail(s: String): Boolean {
        val t = s.trim()
        if (t.length < 5 || t.length > 254) return false
        val at = t.indexOf('@')
        if (at <= 0 || at != t.lastIndexOf('@')) return false
        val domain = t.substring(at + 1)
        if (!domain.contains('.')) return false
        if (domain.startsWith('.') || domain.endsWith('.')) return false
        if (t.any { it.isWhitespace() }) return false
        return true
    }

    private fun b64(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun rfcDate(): String {
        val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US)
        return fmt.format(java.util.Date())
    }
}
