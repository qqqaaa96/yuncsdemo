package sy.great.toolbox.tools

import android.app.Activity
import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import java.nio.charset.Charset

/**
 * NFC 读写核心。
 *
 * 覆盖三类操作：
 *   1. 读 NDEF 消息（文本 / URI / 名片 / WiFi / 蓝牙 等）
 *   2. 写 NDEF 消息
 *   3. 读 Tag 基础信息（UID / 技术 / 容量 / 可写性）
 *
 * 使用方式（页面里）：
 *   onResume  → nfc.enableReaderMode(activity, cb)
 *   onPause   → nfc.disableReaderMode(activity)
 *   在 cb 里拿到 Tag 后调 read / write
 *
 * 重要限制：
 *   · 写卡需要 Tag 未被锁定为只读
 *   · 写卡必须在 Activity 前台切回时立即执行，否则会抛 TagLostException
 *   · NDEF 容量不足会抛 IOException
 */
object NfcHelper {

    /** 解析后的 NDEF 记录 */
    data class Record(
        val typeName: String,
        /** 记录类型：T / U / Sp / text / uri / ... */
        val kind: String,
        val payloadText: String,
        val payloadHex: String,
    ) {
        val display: String
            get() = when (kind) {
                "文本" -> payloadText
                "URI" -> payloadText
                else -> payloadText.ifBlank { payloadHex }
            }
    }

    /** Tag 基础信息 */
    data class TagInfo(
        val uid: String,
        val techList: List<String>,
        val tagType: String,
        val writable: Boolean,
        val maxSize: Int,
        val currentSize: Int,
        val records: List<Record>,
        val rawNdefHex: String,
    )

    /** 写入结果 */
    sealed interface WriteResult {
        data class Ok(val bytesWritten: Int) : WriteResult
        data class Fail(val message: String) : WriteResult
    }

    // ---------------- 可用性 ----------------

    /** 设备是否具备 NFC */
    fun isSupported(ctx: Context): Boolean {
        return try {
            ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC)
        } catch (_: Throwable) {
            false
        }
    }

    /** NFC 是否已经打开 */
    fun isEnabled(ctx: Context): Boolean {
        return try {
            NfcAdapter.getDefaultAdapter(ctx)?.isEnabled == true
        } catch (_: Throwable) {
            false
        }
    }

    // ---------------- Reader Mode ----------------

    /**
     * 开启读卡监听。
     *
     * 用 enableReaderMode 而不是 enableForegroundDispatch：
     *   前者在前台时完全接管 NFC，不会被系统「发现新标签」弹窗抢走，
     *   也不会因为 Activity 切换而丢失回调。
     */
    fun enableReaderMode(activity: Activity, onTag: (Tag) -> Unit): Boolean {
        return try {
            val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return false
            val flags = NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NFC_BARCODE or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

            val extras = Bundle().apply {
                // 有蜂鸣提示，确认已识别
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
            }

            adapter.enableReaderMode(activity, { tag -> onTag(tag) }, flags, extras)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun disableReaderMode(activity: Activity) {
        try {
            NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
        } catch (_: Throwable) {
        }
    }

    // ---------------- 读 ----------------

    /**
     * 读取 Tag 的完整信息。
     *
     * 先试 NDEF，再拿技术信息，最后读原始字节。
     * 任何一步失败都不会中断，尽可能多拿到信息。
     */
    fun read(tag: Tag): TagInfo {
        val uid = tag.id.joinToString("") { b ->
            String.format("%02X", b)
        }

        val techList = tag.techList.map { it.substringAfterLast('.') }

        var writable = false
        var maxSize = 0
        var currentSize = 0
        val records = ArrayList<Record>()
        var rawHex = ""

        // ---- NDEF ----
        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                try {
                    writable = ndef.isWritable
                    maxSize = ndef.maxSize
                    val msg = ndef.ndefMessage
                    if (msg != null) {
                        currentSize = msg.toByteArray().size
                        rawHex = msg.toByteArray().joinToString("") { b ->
                            String.format("%02X", b)
                        }
                        for (r in msg.records) {
                            records.add(parseRecord(r))
                        }
                    }
                } finally {
                    try { ndef.close() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
        }

        // ---- 如果不是 NDEF，但支持格式化，视为可写 ----
        if (records.isEmpty()) {
            try {
                val fmt = NdefFormatable.get(tag)
                if (fmt != null) writable = true
            } catch (_: Throwable) {
            }
        }

        val tagType = when {
            techList.contains("Ndef") -> "NDEF 标签"
            techList.contains("MifareClassic") -> "Mifare Classic"
            techList.contains("MifareUltralight") -> "Mifare Ultralight"
            techList.contains("IsoDep") -> "ISO-DEP (银行卡/身份证)"
            techList.contains("NfcA") -> "NFC-A (Mifare/NTAG)"
            techList.contains("NfcB") -> "NFC-B"
            techList.contains("NfcF") -> "NFC-F (Felica)"
            techList.contains("NfcV") -> "NFC-V"
            else -> "未知"
        }

        return TagInfo(
            uid = uid,
            techList = techList,
            tagType = tagType,
            writable = writable,
            maxSize = maxSize,
            currentSize = currentSize,
            records = records,
            rawNdefHex = rawHex,
        )
    }

    /** 解析一条 NDEF 记录 */
    private fun parseRecord(r: NdefRecord): Record {
        val hex = r.payload.joinToString("") { b -> String.format("%02X", b) }

        return try {
            when (r.tnf) {
                NdefRecord.TNF_WELL_KNOWN -> {
                    when {
                        r.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                            Record("文本", "文本", decodeText(r.payload), hex)
                        }
                        r.type.contentEquals(NdefRecord.RTD_URI) -> {
                            Record("URI", "URI", decodeUri(r.payload), hex)
                        }
                        r.type.contentEquals(NdefRecord.RTD_SMART_POSTER) -> {
                            Record("智能海报", "Sp", "(智能海报)", hex)
                        }
                        else -> Record("其他", "未知", "", hex)
                    }
                }

                NdefRecord.TNF_MIME_MEDIA -> {
                    val mime = String(r.type, Charsets.US_ASCII)
                    val body = when {
                        mime.startsWith("text/") -> String(r.payload, Charsets.UTF_8)
                        mime == "application/vnd.wfa.wsc" -> decodeWifi(r.payload)
                        else -> "(二进制 ${r.payload.size} 字节)"
                    }
                    Record(mime, "MIME", body, hex)
                }

                NdefRecord.TNF_ABSOLUTE_URI -> {
                    Record("绝对 URI", "URI", String(r.type, Charsets.UTF_8), hex)
                }

                NdefRecord.TNF_EXTERNAL_TYPE -> {
                    val t = String(r.type, Charsets.US_ASCII)
                    Record("外部类型", "外部", t, hex)
                }

                NdefRecord.TNF_EMPTY -> Record("空", "空", "", hex)

                else -> Record("TNF=${r.tnf}", "未知", "", hex)
            }
        } catch (_: Throwable) {
            Record("未知", "未知", "", hex)
        }
    }

    /** 解码 RTD_TEXT（首字节是语言码长度 + 编码位） */
    private fun decodeText(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val status = payload[0].toInt() and 0xFF
        val langLen = status and 0x3F
        val isUtf8 = (status and 0x80) != 0
        val from = 1 + langLen
        if (from >= payload.size) return ""
        val body = payload.copyOfRange(from, payload.size)
        val cs = if (isUtf8) Charsets.UTF_8 else Charset.forName("UTF-16")
        return try { String(body, cs) } catch (_: Throwable) { "" }
    }

    /** 解码 RTD_URI（首字节是前缀码） */
    private fun decodeUri(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val prefixes = arrayOf(
            "", "http://www.", "https://www.", "http://", "https://",
            "tel:", "mailto:",
            "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://",
            "sftp://", "smb://", "nfs://", "ftp://", "dav://",
            "news:", "telnet://", "imap:", "rtsp://", "urn:",
            "pop:", "sip:", "sips:", "tftp:", "btspp://",
            "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://",
            "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:",
            "urn:epc:raw:", "urn:epc:", "urn:nfc:",
        )
        val code = payload[0].toInt() and 0xFF
        val prefix = if (code < prefixes.size) prefixes[code] else ""
        val body = payload.copyOfRange(1, payload.size)
        return try { prefix + String(body, Charsets.UTF_8) } catch (_: Throwable) { prefix }
    }

    /** 简易 WiFi 配置解码（只取 SSID，不解析密码） */
    private fun decodeWifi(payload: ByteArray): String {
        return try {
            val s = String(payload, Charsets.UTF_8)
            val idx = s.indexOf("SSID")
            if (idx >= 0 && idx + 6 < s.length) "WiFi: ${s.substring(idx + 5, minOf(idx + 40, s.length)).trim()}"
            else "WiFi 配置（${payload.size} 字节）"
        } catch (_: Throwable) {
            "WiFi 配置"
        }
    }

    // ---------------- 写 ----------------

    /**
     * 写入纯文本 NDEF。
     */
    fun writeText(tag: Tag, text: String): WriteResult {
        return writeMessage(tag, NdefMessage(textRecord(text)))
    }

    /**
     * 写入 URI NDEF。
     */
    fun writeUri(tag: Tag, uri: String): WriteResult {
        return try {
            writeMessage(tag, NdefMessage(NdefRecord.createUri(uri)))
        } catch (e: Throwable) {
            WriteResult.Fail("URI 格式错误：${e.message ?: ""}")
        }
    }

    /**
     * 写入一条自定义 MIME 类型。
     */
    fun writeMime(tag: Tag, mime: String, data: ByteArray): WriteResult {
        return try {
            val rec = NdefRecord(
                NdefRecord.TNF_MIME_MEDIA,
                mime.toByteArray(Charsets.US_ASCII),
                ByteArray(0),
                data,
            )
            writeMessage(tag, NdefMessage(rec))
        } catch (e: Throwable) {
            WriteResult.Fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 构造文本记录（UTF-8，无语言码） */
    private fun textRecord(text: String): NdefRecord {
        return try {
            NdefRecord.createTextRecord("zh", text)
        } catch (_: Throwable) {
            // 低版本兼容
            val lang = "zh".toByteArray(Charsets.US_ASCII)
            val body = text.toByteArray(Charsets.UTF_8)
            val payload = ByteArray(1 + lang.size + body.size)
            payload[0] = lang.size.toByte()
            System.arraycopy(lang, 0, payload, 1, lang.size)
            System.arraycopy(body, 0, payload, 1 + lang.size, body.size)
            NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, ByteArray(0), payload)
        }
    }

    /**
     * 写 NDEF 消息。
     *
     * 三种情况：
     *   1. 已经是 NDEF 标签 → 直接写
     *   2. 不是 NDEF 但可格式化 → 先格式化再写
     *   3. 都不行 → 失败
     */
    fun writeMessage(tag: Tag, msg: NdefMessage): WriteResult {
        // ---- 情况 1：标准 NDEF ----
        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                try {
                    if (!ndef.isWritable) {
                        return WriteResult.Fail("标签已锁定为只读，无法写入")
                    }
                    if (msg.toByteArray().size > ndef.maxSize) {
                        return WriteResult.Fail(
                            "内容太大：需要 ${msg.toByteArray().size} 字节，标签容量 ${ndef.maxSize} 字节"
                        )
                    }
                    ndef.writeNdefMessage(msg)
                    return WriteResult.Ok(msg.toByteArray().size)
                } finally {
                    try { ndef.close() } catch (_: Throwable) {}
                }
            }
        } catch (e: Throwable) {
            return WriteResult.Fail(
                "写入失败：${e.javaClass.simpleName}（标签可能已移开）"
            )
        }

        // ---- 情况 2：可格式化的空白标签 ----
        try {
            val fmt = NdefFormatable.get(tag)
            if (fmt != null) {
                fmt.connect()
                try {
                    fmt.format(msg)
                    return WriteResult.Ok(msg.toByteArray().size)
                } finally {
                    try { fmt.close() } catch (_: Throwable) {}
                }
            }
        } catch (e: Throwable) {
            return WriteResult.Fail("格式化失败：${e.message ?: e.javaClass.simpleName}")
        }

        return WriteResult.Fail("该标签不支持 NDEF 写入")
    }

    // ---------------- 原始读写 ----------------

    /**
     * 读取底层芯片原始字节（仅 Mifare Ultralight / NTAG）。
     *
     * 用于查看非 NDEF 标签的存储内容。
     */
    fun readRawPages(tag: Tag, fromPage: Int = 0, pageCount: Int = 16): ByteArray? {
        val tech = MifareUltralight.get(tag) ?: return null
        return try {
            tech.connect()
            try {
                val out = java.io.ByteArrayOutputStream()
                var page = fromPage
                var left = pageCount
                while (left > 0) {
                    val chunk = tech.readPages(page) ?: break
                    out.write(chunk)
                    page += chunk.size / 4
                    left--
                }
                out.toByteArray()
            } finally {
                try { tech.close() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            null
        }
    }
}
