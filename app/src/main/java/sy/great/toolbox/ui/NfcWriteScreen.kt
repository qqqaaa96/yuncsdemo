package sy.great.toolbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.ui.theme.LocalPalette

/**
 * NFC 写入：选类型 + 填内容。
 *
 * 按 NFC Tools 的做法，把常用记录类型都列出来，
 * 选中后在下方填表，底部按钮进写入页。
 *
 * 每种类型最终都转成一个标准 NDEF 记录：
 *   · 文本   → TNF_WELL_KNOWN / RTD_TEXT
 *   · 网址   → TNF_WELL_KNOWN / RTD_URI
 *   · 电话   → RTD_URI, tel:
 *   · 短信   → RTD_URI, smsto:
 *   · 邮件   → RTD_URI, mailto:
 *   · WiFi   → TNF_MIME_MEDIA, application/vnd.wfa.wsc
 *   · 名片   → TNF_MIME_MEDIA, text/vcard
 */
@Composable
fun NfcWriteScreen(
    onBack: () -> Unit,
    /** 填好内容后，带着 payload 进读卡页 */
    onRequestWrite: (WritePayload) -> Unit,
) {
    val pal = LocalPalette.current

    var kind by remember { mutableStateOf(RecordKind.Text) }
    var field1 by remember { mutableStateOf("") }
    var field2 by remember { mutableStateOf("") }
    var field3 by remember { mutableStateOf("") }

    // 当前类型的错误提示
    val err = validate(kind, field1, field2, field3)

    Column(Modifier.fillMaxSize().background(pal.paper)) {
        PageHeader("写入标签", onBack)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(10.dp))

            // ---- 类型 ----
            Text("记录类型", fontSize = 12.sp, color = pal.inkSoft)
            Spacer(Modifier.height(8.dp))

            RecordKind.entries.chunked(3).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { k ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (k == kind) pal.accent else pal.paperSoft)
                                .clickable {
                                    kind = k
                                    field1 = ""; field2 = ""; field3 = ""
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                k.label,
                                fontSize = 12.sp,
                                fontWeight = if (k == kind) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (k == kind) Color.White else pal.inkSoft,
                                maxLines = 1,
                            )
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(14.dp))

            // ---- 输入 ----
            when (kind) {
                RecordKind.Text -> {
                    Field("文本内容", field1, multiline = true) { field1 = it }
                }

                RecordKind.Uri -> {
                    Field("网址", field1) { field1 = it }
                }

                RecordKind.Tel -> {
                    Field("电话号码", field1, numberOnly = true) { field1 = it }
                }

                RecordKind.Sms -> {
                    Field("电话号码", field1, numberOnly = true) { field1 = it }
                    Spacer(Modifier.height(10.dp))
                    Field("短信内容", field2, multiline = true) { field2 = it }
                }

                RecordKind.Mail -> {
                    Field("收件人邮箱", field1) { field1 = it }
                    Spacer(Modifier.height(10.dp))
                    Field("主题", field2) { field2 = it }
                    Spacer(Modifier.height(10.dp))
                    Field("正文", field3, multiline = true) { field3 = it }
                }

                RecordKind.Wifi -> {
                    Field("WiFi 名称（SSID）", field1) { field1 = it }
                    Spacer(Modifier.height(10.dp))
                    Field("密码", field2) { field2 = it }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("WPA2", "WPA", "WEP", "开放").forEach { t ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (field3 == t) pal.accent else pal.paperSoft)
                                    .clickable { field3 = t },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    t,
                                    fontSize = 11.5.sp,
                                    color = if (field3 == t) Color.White else pal.inkSoft,
                                )
                            }
                        }
                    }
                }

                RecordKind.VCard -> {
                    Field("姓名", field1) { field1 = it }
                    Spacer(Modifier.height(10.dp))
                    Field("电话", field2) { field2 = it }
                    Spacer(Modifier.height(10.dp))
                    Field("邮箱", field3) { field3 = it }
                }
            }

            // ---- 错误 ----
            if (err != null) {
                Spacer(Modifier.height(10.dp))
                Text(err, fontSize = 12.sp, color = sy.great.toolbox.ui.theme.Bad)
            }

            Spacer(Modifier.height(20.dp))

            // ---- 写入按钮 ----
            val canWrite = err == null && field1.isNotBlank()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (canWrite) pal.accent else pal.paperSoft)
                    .clickable(enabled = canWrite) {
                        onRequestWrite(buildPayload(kind, field1, field2, field3))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "下一步：靠卡写入",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (canWrite) Color.White else pal.inkSoft,
                )
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}

// ---------------- 类型定义 ----------------

enum class RecordKind(val label: String) {
    Text("文本"),
    Uri("网址"),
    Tel("电话"),
    Sms("短信"),
    Mail("邮件"),
    Wifi("WiFi"),
    VCard("名片"),
}

// ---------------- 校验 ----------------

private fun validate(k: RecordKind, f1: String, f2: String, f3: String): String? {
    return when (k) {
        RecordKind.Text -> if (f1.isBlank()) "请输入文本" else null

        RecordKind.Uri -> when {
            f1.isBlank() -> "请输入网址"
            !(f1.startsWith("http://") || f1.startsWith("https://")) -> "网址需要以 http:// 或 https:// 开头"
            else -> null
        }

        RecordKind.Tel -> when {
            f1.isBlank() -> "请输入电话号码"
            !f1.all { it.isDigit() || it == '+' || it == '-' } -> "电话号码只能包含数字和 + -"
            else -> null
        }

        RecordKind.Sms -> when {
            f1.isBlank() -> "请输入电话号码"
            !f1.all { it.isDigit() || it == '+' } -> "电话号码只能包含数字和 +"
            else -> null
        }

        RecordKind.Mail -> when {
            f1.isBlank() -> "请输入邮箱"
            !f1.contains('@') -> "邮箱格式不正确"
            else -> null
        }

        RecordKind.Wifi -> when {
            f1.isBlank() -> "请输入 WiFi 名称"
            f3.isBlank() -> "请选择加密方式"
            f3 != "开放" && f2.isBlank() -> "加密网络需要密码"
            else -> null
        }

        RecordKind.VCard -> if (f1.isBlank()) "请输入姓名" else null
    }
}

// ---------------- 构造 payload ----------------

private fun buildPayload(k: RecordKind, f1: String, f2: String, f3: String): WritePayload {
    return when (k) {
        RecordKind.Text -> WritePayload(WriteKind.Text, text = f1)

        RecordKind.Uri -> WritePayload(WriteKind.Uri, text = f1)

        RecordKind.Tel -> WritePayload(WriteKind.Uri, text = "tel:$f1")

        RecordKind.Sms -> {
            val body = if (f2.isNotBlank()) "?body=${urlEncode(f2)}" else ""
            WritePayload(WriteKind.Uri, text = "smsto:$f1$body")
        }

        RecordKind.Mail -> {
            val parts = ArrayList<String>()
            if (f2.isNotBlank()) parts.add("subject=" + urlEncode(f2))
            if (f3.isNotBlank()) parts.add("body=" + urlEncode(f3))
            val q = if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
            WritePayload(WriteKind.Uri, text = "mailto:$f1$q")
        }

        RecordKind.Wifi -> {
            // WiFi 配置用标准 WSC 二进制格式
            val bytes = buildWsc(f1, f2, f3)
            WritePayload(WriteKind.Mime, mime = "application/vnd.wfa.wsc", bytes = bytes)
        }

        RecordKind.VCard -> {
            val vcf = buildString {
                append("BEGIN:VCARD\r\n")
                append("VERSION:3.0\r\n")
                append("FN:").append(f1).append("\r\n")
                if (f2.isNotBlank()) append("TEL:").append(f2).append("\r\n")
                if (f3.isNotBlank()) append("EMAIL:").append(f3).append("\r\n")
                append("END:VCARD\r\n")
            }
            WritePayload(WriteKind.Mime, mime = "text/vcard", bytes = vcf.toByteArray(Charsets.UTF_8))
        }
    }
}

private fun urlEncode(s: String): String =
    try { java.net.URLEncoder.encode(s, "UTF-8") } catch (_: Throwable) { s }

/**
 * 构造 WiFi Simple Configuration（WSC）二进制。
 *
 * 结构：一串 TLV（2 字节 type + 2 字节 length + value），
 * 最后用 WSC_END 收尾。这是 NDEF WiFi 记录的标准格式，
 * 安卓系统识别后能直接弹出「连接到此网络」。
 */
private fun buildWsc(ssid: String, password: String, security: String): ByteArray {
    val out = java.io.ByteArrayOutputStream()

    fun tlv(type: Int, value: ByteArray) {
        out.write((type shr 8) and 0xFF)
        out.write(type and 0xFF)
        out.write((value.size shr 8) and 0xFF)
        out.write(value.size and 0xFF)
        out.write(value)
    }

    // 0x1045 = SSID
    tlv(0x1045, ssid.toByteArray(Charsets.UTF_8))

    // 0x1003 = 认证类型
    val authType = when (security) {
        "开放" -> 0x0001
        "WEP" -> 0x0002
        "WPA" -> 0x0002
        else -> 0x0020  // WPA2-PSK
    }
    tlv(0x1003, byteArrayOf((authType shr 8).toByte(), authType.toByte()))

    // 0x100F = 加密类型（开放网络不需要）
    if (security != "开放") {
        val encType = if (security == "WEP") 0x0002 else 0x0008  // WEP / AES
        tlv(0x100F, byteArrayOf((encType shr 8).toByte(), encType.toByte()))
    }

    // 0x1027 = 网络密钥
    if (security != "开放" && password.isNotBlank()) {
        tlv(0x1027, password.toByteArray(Charsets.UTF_8))
    }

    // 0x1020 = MAC 地址（全 0 表示任意）
    tlv(0x1020, ByteArray(6))

    // 0x104A = 版本 2.0
    tlv(0x104A, byteArrayOf(0x10, 0x4A, 0x00, 0x01, 0x10))

    // 结束标记
    out.write(0x00)
    out.write(0x00)

    return out.toByteArray()
}

// ---------------- 输入框 ----------------

@Composable
private fun Field(
    label: String,
    value: String,
    multiline: Boolean = false,
    numberOnly: Boolean = false,
    onChange: (String) -> Unit,
) {
    val pal = LocalPalette.current

    Column {
        Text(label, fontSize = 12.sp, color = pal.inkSoft)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(pal.paperSoft)
                .padding(12.dp),
        ) {
            if (value.isEmpty()) {
                Text(label, fontSize = 13.5.sp, color = pal.inkSoft.copy(alpha = 0.6f))
            }
            BasicTextField(
                value = value,
                onValueChange = { v ->
                    // 数字键盘也允许粘贴字母，这里再过滤一次
                    onChange(if (numberOnly) v.filter { it.isDigit() || it == '+' || it == '-' } else v)
                },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 13.5.sp,
                    color = pal.ink,
                ),
                cursorBrush = SolidColor(pal.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (multiline) 80.dp else 22.dp),
            )
        }
    }
}
