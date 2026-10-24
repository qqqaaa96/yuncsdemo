package sy.great.toolbox.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.tools.NfcHelper
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.LocalPalette

/**
 * 从任意 Context 找出宿主 Activity。
 *
 * enableReaderMode 必须传 Activity，而 Compose 里拿到的是 ContextWrapper。
 */
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * NFC 读卡 / 写卡共用页。
 *
 * 同一套靠卡逻辑，两种模式：
 *   · Read  ：拿卡 → 展示详情
 *   · Write ：选好类型填好内容 → 拿卡 → 写入
 *
 * 关键时序（必须这样，否则写不进去）：
 *   1. 进页面 enableReaderMode
 *   2. 用户把标签贴上来，回调拿到 Tag
 *   3. 立即在同一回调线程里读 / 写
 *   4. 完成后不要关 reader mode，允许用户继续贴下一张
 */
@Composable
fun NfcReadScreen(
    onBack: () -> Unit,
    /** 写入模式下要写的内容；读模式传 null */
    writePayload: WritePayload? = null,
) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val activity = remember(ctx) { ctx.findActivity() }

    var lastTag by remember { mutableStateOf<NfcHelper.TagInfo?>(null) }
    var status by remember { mutableStateOf("请将标签贴近手机背面") }
    var statusBad by remember { mutableStateOf(false) }

    // 用 AtomicBoolean 做写入锁：
    // 普通 Boolean 的“先判断再赋值”不是原子操作，
    // 连续两次靠卡回调可能同时通过检查，导致重复写入。
    val writing = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    val isWrite = writePayload != null

    DisposableEffect(Unit) {
        val ok = activity?.let { a ->
            NfcHelper.enableReaderMode(a) { tag ->
                // 回调不在主线程，写要放在后台
                Thread {
                    try {
                        if (isWrite) {
                            if (!writing.compareAndSet(false, true)) return@Thread

                            val result = when (writePayload!!.kind) {
                                WriteKind.Text -> NfcHelper.writeText(tag, writePayload.text)
                                WriteKind.Uri -> NfcHelper.writeUri(tag, writePayload.text)
                                WriteKind.Mime -> NfcHelper.writeMime(
                                    tag, writePayload.mime, writePayload.bytes
                                )
                            }

                            when (result) {
                                is NfcHelper.WriteResult.Ok -> {
                                    status = "写入成功（${result.bytesWritten} 字节），可以移开标签"
                                    statusBad = false
                                }
                                is NfcHelper.WriteResult.Fail -> {
                                    status = result.message
                                    statusBad = true
                                }
                            }
                            writing.set(false)
                        } else {
                            val info = NfcHelper.read(tag)
                            lastTag = info
                            status = "已读取：${info.tagType}"
                            statusBad = false
                        }
                    } catch (e: Throwable) {
                        status = e.message ?: e.javaClass.simpleName
                        statusBad = true
                        writing.set(false)
                    }
                }.start()
            }
        } ?: false

        if (!ok) {
            status = "无法启用 NFC 读卡（设备未开启 NFC 或权限不足）"
            statusBad = true
        }

        onDispose {
            activity?.let { NfcHelper.disableReaderMode(it) }
        }
    }

    Column(Modifier.fillMaxSize().background(pal.paper)) {
        PageHeader(if (isWrite) "写入标签" else "读取标签", onBack)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            // ---- 状态 ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (statusBad) Bad.copy(alpha = 0.08f)
                        else Accent.copy(alpha = 0.08f)
                    )
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    NfcPulseIcon(if (statusBad) Bad else Accent)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        status,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = if (statusBad) Bad else Accent,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }

            // ---- 写入内容预览 ----
            if (isWrite) {
                Spacer(Modifier.height(14.dp))
                Text("将要写入", fontSize = 12.sp, color = pal.inkSoft)
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(pal.paperSoft)
                        .padding(12.dp),
                ) {
                    Text(
                        writePayload!!.preview,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = pal.ink,
                    )
                }
            }

            // ---- 读到的内容 ----
            val info = lastTag
            if (!isWrite && info != null) {
                Spacer(Modifier.height(16.dp))
                TagDetail(info)
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}

/** 写入类型 */
enum class WriteKind { Text, Uri, Mime }

/** 写入参数 */
data class WritePayload(
    val kind: WriteKind,
    val text: String = "",
    val mime: String = "",
    val bytes: ByteArray = ByteArray(0),
) {
    val preview: String
        get() = when (kind) {
            WriteKind.Text -> "文本：$text"
            WriteKind.Uri -> "网址：$text"
            WriteKind.Mime -> "$mime（${bytes.size} 字节）"
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WritePayload) return false
        return kind == other.kind && text == other.text &&
                mime == other.mime && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var r = kind.hashCode()
        r = 31 * r + text.hashCode()
        r = 31 * r + mime.hashCode()
        r = 31 * r + bytes.contentHashCode()
        return r
    }
}

@Composable
private fun TagDetail(info: NfcHelper.TagInfo) {
    val pal = LocalPalette.current

    Column(Modifier.fillMaxWidth()) {
        // ---- 基础信息 ----
        NfcSectionTitle("基础信息")
        InfoRow("类型", info.tagType)
        InfoRow("UID", info.uid, mono = true)
        InfoRow("可写", if (info.writable) "是" else "否（只读）")
        if (info.maxSize > 0) {
            InfoRow("容量", "${info.currentSize} / ${info.maxSize} 字节")
        }
        InfoRow("技术", info.techList.joinToString(", "))

        // ---- NDEF 记录 ----
        if (info.records.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            NfcSectionTitle("标签内容（${info.records.size} 条记录）")

            info.records.forEachIndexed { i, r ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(pal.paperSoft)
                        .padding(12.dp),
                ) {
                    Column {
                        Text(
                            "${i + 1}. ${r.kind}" +
                                    if (r.typeName.isNotEmpty() && r.typeName != r.kind) "（${r.typeName}）" else "",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = pal.accent,
                        )
                        Spacer(Modifier.height(5.dp))
                        SelectionContainer {
                            Text(
                                r.display.ifBlank { "(空)" },
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                color = pal.ink,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        } else {
            Spacer(Modifier.height(18.dp))
            NfcSectionTitle("标签内容")
            Text(
                "这个标签没有 NDEF 内容（可能是空白标签或非 NDEF 标签）",
                fontSize = 12.5.sp,
                color = pal.inkSoft,
            )
        }

        // ---- 原始数据 ----
        if (info.rawNdefHex.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            NfcSectionTitle("原始 NDEF 字节")
            SelectionContainer {
                Text(
                    info.rawNdefHex,
                    fontSize = 10.5.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    color = pal.inkSoft,
                )
            }
        }
    }
}

@Composable
private fun NfcSectionTitle(text: String) {
    val pal = LocalPalette.current
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = pal.inkSoft,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    val pal = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            color = pal.inkSoft,
            modifier = Modifier.width(56.dp),
        )
        SelectionContainer {
            Text(
                value.ifBlank { "—" },
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = pal.ink,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 简单的 NFC 波纹图标（矢量，随颜色变化） */
@Composable
private fun NfcPulseIcon(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(44.dp)) {
        val s = size.minDimension
        val cx = s * 0.30f
        val cy = s / 2f
        val w = s * 0.055f

        // 三个同心弧（由小到大）
        val radii = floatArrayOf(s * 0.26f, s * 0.40f, s * 0.54f)
        radii.forEachIndexed { i, r ->
            drawArc(
                color = color.copy(alpha = 0.35f + i * 0.25f),
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(cx - r, cy - r),
                size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = w,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                ),
            )
        }

        // 圆心点
        drawCircle(color, radius = w * 1.2f, center = androidx.compose.ui.geometry.Offset(cx, cy))
    }
}
