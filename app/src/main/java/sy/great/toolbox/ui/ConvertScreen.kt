package sy.great.toolbox.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sy.great.toolbox.tools.AudioConverter
import sy.great.toolbox.tools.ExportUtils
import sy.great.toolbox.tools.FileImporter
import sy.great.toolbox.tools.ImageConverter
import sy.great.toolbox.tools.VideoConverter
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.Good
import sy.great.toolbox.ui.theme.LocalPalette
import sy.great.toolbox.ui.theme.Warn
import java.io.File
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.Good
import sy.great.toolbox.ui.theme.LocalPalette
import sy.great.toolbox.ui.theme.Warn
import java.io.File

/** 转换类型 */
private enum class ConvertKind(val title: String) {
    Image("图片"),
    Audio("音频"),
    Video("视频")
}

/**
 * 格式转换页。
 *
 * 三类能力：
 *   · 图片：PNG / JPEG / WebP 互转，带质量与尺寸
 *   · 音频：任意音频 → WAV（无损，一定有结果）
 *   · 视频：重封装 / 提取音频 / 转 GIF
 *
 * 设计原则：能做到就做，做不到就明确告知，不放假按钮。
 */
@Composable
fun ConvertScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(ConvertKind.Image) }
    var imported by remember { mutableStateOf<FileImporter.Imported?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var statusBad by remember { mutableStateOf(false) }

    // 图片参数
    var imgFormat by remember { mutableStateOf(ImageConverter.Format.JPEG) }
    var imgQuality by remember { mutableStateOf(90) }
    var imgMaxSide by remember { mutableStateOf(0) }

    // 视频参数
    var videoOp by remember { mutableStateOf(0) }   // 0=重封装 MP4 1=MKV 2=提取音频 3=GIF

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "正在导入..."
        statusBad = false
        scope.launch {
            val imp = withContext(Dispatchers.IO) { FileImporter.importFromUri(ctx, uri) }
            busy = false
            if (imp == null) {
                status = "导入失败，无法读取该文件"
                statusBad = true
            } else {
                imported = imp
                status = "已选择：" + imp.displayName
                statusBad = false
            }
        }
    }

    fun runConvert() {
        val imp = imported ?: run {
            status = "请先选择文件"
            statusBad = true
            return
        }
        busy = true
        progress = 0f
        status = "转换中..."
        statusBad = false

        scope.launch {
            // 显式指定返回类型 String：
            // 里面有多条 return@withContext 分支，不标注会让推断失败。
            val msg = withContext<String>(Dispatchers.IO) {
                try {
                    val src = File(imp.localPath)
                    when (kind) {
                        ConvertKind.Image -> {
                            val r = ImageConverter.convert(
                                src,
                                ImageConverter.Options(imgFormat, imgQuality, imgMaxSide)
                            )
                            if (!r.ok) return@withContext "失败：" + r.message
                            val base = imp.displayName.substringBeforeLast('.', imp.displayName)
                            val out = ExportUtils.exportBytes(
                                ctx, "$base.${imgFormat.ext}", r.bytes, imgFormat.mime
                            )
                            if (!out.ok) "导出失败：" + out.message
                            else "完成：${r.width}×${r.height}，${fmtSize(r.bytes.size)}\n已保存到 ${out.displayPath}"
                        }

                        ConvertKind.Audio -> {
                            val r = AudioConverter.toWav(
                                input = src,
                                progress = { p -> progress = p }
                            )
                            if (!r.ok) return@withContext "失败：" + r.message
                            val base = imp.displayName.substringBeforeLast('.', imp.displayName)
                            val out = ExportUtils.exportBytes(
                                ctx, "$base.wav", r.bytes, "audio/wav"
                            )
                            if (!out.ok) "导出失败：" + out.message
                            else "完成：${r.sampleRate}Hz ${r.channels}声道，${fmtSize(r.bytes.size)}\n已保存到 ${out.displayPath}"
                        }

                        ConvertKind.Video -> {
                            val base = imp.displayName.substringBeforeLast('.', imp.displayName)
                            when (videoOp) {
                                0, 1 -> {
                                    val fmt = if (videoOp == 0)
                                        android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                                    else android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                                    val ext = if (videoOp == 0) "mp4" else "webm"
                                    val tmp = File(ctx.cacheDir, "remux_$base.$ext")
                                    val r = VideoConverter.remux(src, tmp, fmt)
                                    if (!r.ok) return@withContext "失败：" + r.message
                                    val bytes = tmp.readBytes()
                                    tmp.delete()
                                    val out = ExportUtils.exportBytes(
                                        ctx, "$base.$ext", bytes,
                                        if (videoOp == 0) "video/mp4" else "video/webm"
                                    )
                                    if (!out.ok) "导出失败：" + out.message
                                    else "完成：${fmtSize(bytes.size)}\n已保存到 ${out.displayPath}"
                                }
                                2 -> {
                                    val r = AudioConverter.toWav(
                                        input = src,
                                        progress = { p -> progress = p }
                                    )
                                    if (!r.ok) return@withContext "失败：" + r.message
                                    val out = ExportUtils.exportBytes(
                                        ctx, "$base.wav", r.bytes, "audio/wav"
                                    )
                                    if (!out.ok) "导出失败：" + out.message
                                    else "完成：已提取音轨 ${fmtSize(r.bytes.size)}\n已保存到 ${out.displayPath}"
                                }
                                else -> {
                                    val r = VideoConverter.toGif(
                                        input = src,
                                        progress = { p -> progress = p }
                                    )
                                    if (!r.ok) return@withContext "失败：" + r.message
                                    val out = ExportUtils.exportBytes(
                                        ctx, "$base.gif", r.bytes, "image/gif"
                                    )
                                    if (!out.ok) "导出失败：" + out.message
                                    else "完成：${r.width}×${r.height}，${fmtSize(r.bytes.size)}\n已保存到 ${out.displayPath}"
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    "异常：" + (e.message ?: e.javaClass.simpleName)
                }
            }
            busy = false
            status = msg
            statusBad = msg.startsWith("失败") || msg.startsWith("异常") || msg.startsWith("导出失败")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(pal.paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        PageHeader(title = "格式转换", onBack = onBack)
        Spacer(Modifier.height(10.dp))

        // ---- 类型切换 ----
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in ConvertKind.values()) {
                SegmentChip(k.title, kind == k, { kind = k; imported = null; status = "" })
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 选择文件 ----
        SectionCard(pal) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("源文件", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink)
                    Text(
                        imported?.displayName ?: "未选择",
                        fontSize = 11.sp, color = pal.inkSoft,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(10.dp))
                PillButton("选择", enabled = !busy) {
                    val mime = when (kind) {
                        ConvertKind.Image -> arrayOf("image/*")
                        ConvertKind.Audio -> arrayOf("audio/*")
                        ConvertKind.Video -> arrayOf("video/*")
                    }
                    picker.launch(mime)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 参数 ----
        when (kind) {
            ConvertKind.Image -> ImageOptions(
                pal, imgFormat, { imgFormat = it },
                imgQuality, { imgQuality = it },
                imgMaxSide, { imgMaxSide = it }
            )
            ConvertKind.Audio -> AudioOptions(pal)
            ConvertKind.Video -> VideoOptions(pal, videoOp) { videoOp = it }
        }

        Spacer(Modifier.height(16.dp))

        // ---- 执行 ----
        Row(Modifier.fillMaxWidth()) {
            PillButton(
                text = if (busy) "处理中..." else "开始转换",
                enabled = !busy && imported != null,
                primary = true
            ) { runConvert() }
        }

        if (busy) {
            Spacer(Modifier.height(10.dp))
            ProgressBar(progress, pal)
        }

        if (status.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                status,
                fontSize = 12.sp,
                color = if (statusBad) Bad else Good,
                lineHeight = 18.sp
            )
        }

        Spacer(Modifier.height(120.dp))
    }
}

// ---------------- 子组件 ----------------

@Composable
private fun SectionCard(pal: sy.great.toolbox.ui.theme.Palette, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.paperSoft, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) { content() }
}

@Composable
private fun SegmentChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val bg = if (selected) Accent else pal.paperSoft
    val fg = if (selected) Color.White else pal.ink
    Row(
        Modifier
            .background(bg, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text, fontSize = 13.sp, color = fg, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PillButton(
    text: String,
    enabled: Boolean,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    val pal = LocalPalette.current
    val bg = when {
        !enabled -> pal.line
        primary -> Accent
        else -> pal.paperSoft
    }
    val fg = when {
        !enabled -> pal.inkSoft
        primary -> Color.White
        else -> pal.ink
    }
    Row(
        Modifier
            .background(bg, RoundedCornerShape(18.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(text, fontSize = 13.sp, color = fg, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ImageOptions(
    pal: sy.great.toolbox.ui.theme.Palette,
    format: ImageConverter.Format,
    onFormat: (ImageConverter.Format) -> Unit,
    quality: Int,
    onQuality: (Int) -> Unit,
    maxSide: Int,
    onMaxSide: (Int) -> Unit
) {
    SectionCard(pal) {
        Text("输出格式", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in ImageConverter.Format.values()) {
                SegmentChip(f.display, format == f) { onFormat(f) }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "质量：$quality" + if (format == ImageConverter.Format.PNG) "（PNG 为无损，此值无效）" else "",
            fontSize = 12.sp, color = pal.inkSoft
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (q in listOf(60, 75, 85, 90, 100)) {
                SegmentChip("$q", quality == q) { onQuality(q) }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "最大边长：" + (if (maxSide == 0) "不缩放" else "$maxSide px"),
            fontSize = 12.sp, color = pal.inkSoft
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SegmentChip("不缩放", maxSide == 0) { onMaxSide(0) }
            SegmentChip("1024", maxSide == 1024) { onMaxSide(1024) }
            SegmentChip("512", maxSide == 512) { onMaxSide(512) }
            SegmentChip("256", maxSide == 256) { onMaxSide(256) }
        }
    }
}

@Composable
private fun AudioOptions(pal: sy.great.toolbox.ui.theme.Palette) {
    SectionCard(pal) {
        Text("输出格式：WAV（无损 PCM）", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "WAV 不压缩，体积较大，但能完整保留原始音质。\n" +
            "支持输入：MP3 / AAC / M4A / FLAC / OGG / OPUS / AMR，\n" +
            "也可直接把视频当作源文件来提取音轨。",
            fontSize = 11.sp, color = pal.inkSoft, lineHeight = 17.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "不提供 MP3 输出：Android 系统没有 MP3 编码器，做不了。",
            fontSize = 11.sp, color = Warn, lineHeight = 17.sp
        )
    }
}

@Composable
private fun VideoOptions(pal: sy.great.toolbox.ui.theme.Palette, op: Int, onOp: (Int) -> Unit) {
    SectionCard(pal) {
        Text("处理方式", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink)
        Spacer(Modifier.height(8.dp))
        val ops = listOf(
            "转 MP4（重封装，无损）",
            "转 WebM（仅限 VP8/VP9）",
            "提取音轨为 WAV",
            "抽帧转 GIF"
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in ops.indices) {
                SegmentChip(ops[i], op == i) { onOp(i) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "重封装不重新编码，速度极快且无损。\n" +
            "不支持改分辨率/码率（需要 x264，体积不现实）。\n" +
            "转 GIF 会抽取前 60 帧、10fps、长边 480px 控制体积。",
            fontSize = 11.sp, color = pal.inkSoft, lineHeight = 17.sp
        )
    }
}

@Composable
private fun ProgressBar(progress: Float, pal: sy.great.toolbox.ui.theme.Palette) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(pal.line, RoundedCornerShape(3.dp))
        ) {
            Row(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .background(Accent, RoundedCornerShape(3.dp))
            ) {}
        }
        Spacer(Modifier.height(4.dp))
        Text("${(progress * 100).toInt()}%", fontSize = 11.sp, color = pal.inkSoft)
    }
}

private fun fmtSize(bytes: Int): String {
    val b = bytes.toLong()
    return when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format("%.1f KB", b / 1024.0)
        else -> String.format("%.2f MB", b / 1024.0 / 1024.0)
    }
}
