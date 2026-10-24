package sy.great.toolbox.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.tools.CodeMaker
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.LocalPalette
import java.io.File

/**
 * 二维码 / 条形码生成页。
 *
 * 布局：
 *   类型选择 → 内容输入 → 实时预览 → 保存/分享
 *
 * 实时生成：内容一变就重新画（生成很轻，100ms 内）。
 */
@Composable
fun CodeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current

    var kind by remember { mutableStateOf(CodeMaker.Kind.QR) }
    var content by remember { mutableStateOf("") }

    // 校验错误
    val err = if (content.isBlank()) null else CodeMaker.validate(content, kind)

    // 位图：内容 / 类型变了就重算
    val bitmap: Bitmap? = remember(content, kind) {
        if (content.isBlank() || err != null) null
        else CodeMaker.make(content, kind, 1000)
    }

    var savedMsg by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(pal.paper)) {
        PageHeader("码生成", onBack)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ---- 类型 ----
            Text("类型", fontSize = 12.sp, color = pal.inkSoft)
            Spacer(Modifier.height(6.dp))

            // 两行流式排列
            val kinds = CodeMaker.Kind.entries
            for (row in kinds.chunked(4)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { k ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (k == kind) pal.accent else pal.paperSoft)
                                .clickable { kind = k; savedMsg = "" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                k.label,
                                fontSize = 11.sp,
                                fontWeight = if (k == kind) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (k == kind) Color.White else pal.inkSoft,
                                maxLines = 1,
                            )
                        }
                    }
                    // 补空位
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(4.dp))
            Text(kind.hint, fontSize = 11.sp, color = pal.inkSoft)

            Spacer(Modifier.height(14.dp))

            // ---- 内容 ----
            Text("内容", fontSize = 12.sp, color = pal.inkSoft)
            Spacer(Modifier.height(6.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(pal.paperSoft)
                    .padding(12.dp),
            ) {
                if (content.isEmpty()) {
                    Text("输入要编码的内容", fontSize = 14.sp, color = pal.inkSoft)
                }
                BasicTextField(
                    value = content,
                    onValueChange = { content = it; savedMsg = "" },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 14.sp,
                        color = pal.ink,
                    ),
                    cursorBrush = SolidColor(pal.accent),
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }

            if (err != null) {
                Spacer(Modifier.height(6.dp))
                Text(err, fontSize = 12.sp, color = Bad)
            }

            Spacer(Modifier.height(18.dp))

            // ---- 预览 ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    // 按原图比例缩放到宽度 280dp，高度跟着算
                    val maxW = 280.dp
                    val ratio = bmp.height.toFloat() / bmp.width.toFloat()

                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "生成的码",
                        modifier = Modifier
                            .width(maxW)
                            .height(maxW * ratio),
                    )
                } else {
                    Box(Modifier.height(200.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (content.isBlank()) "输入内容后显示" else "无法生成，请检查内容",
                            fontSize = 13.sp,
                            color = Color(0xFFAAAAAA),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- 操作 ----
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(
                    label = "保存图片",
                    enabled = bitmap != null,
                    modifier = Modifier.weight(1f),
                ) {
                    val bmp = bitmap ?: return@ActionButton
                    val f = saveBitmap(ctx, bmp, kind)
                    savedMsg = if (f != null) "已保存到相册：${f.name}" else "保存失败（检查存储权限）"
                }

                ActionButton(
                    label = "分享",
                    enabled = bitmap != null,
                    modifier = Modifier.weight(1f),
                    secondary = true,
                ) {
                    val bmp = bitmap ?: return@ActionButton
                    val f = saveBitmap(ctx, bmp, kind)
                    if (f != null) shareImage(ctx, f)
                }
            }

            if (savedMsg.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(savedMsg, fontSize = 12.sp, color = pal.inkSoft)
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    onClick: () -> Unit,
) {
    val pal = LocalPalette.current
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    !enabled -> pal.paperSoft
                    secondary -> pal.paperSoft
                    else -> pal.accent
                }
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                !enabled -> pal.inkSoft
                secondary -> pal.accent
                else -> Color.White
            },
        )
    }
}

/** 保存 PNG 到相册的 Pictures 目录 */
private fun saveBitmap(
    ctx: android.content.Context,
    bmp: Bitmap,
    kind: CodeMaker.Kind,
): File? {
    return try {
        val dir = File("/storage/emulated/0/Pictures/工具箱")
        if (!dir.exists()) dir.mkdirs()

        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val f = File(dir, "${kind.name}_$ts.png")

        java.io.FileOutputStream(f).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        // 通知相册刷新
        try {
            android.media.MediaScannerConnection.scanFile(
                ctx, arrayOf(f.absolutePath), arrayOf("image/png"), null
            )
        } catch (_: Throwable) {
        }

        f
    } catch (_: Throwable) {
        null
    }
}

private fun shareImage(ctx: android.content.Context, f: File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, ctx.packageName + ".fileprovider", f
        )
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "分享二维码"))
    } catch (_: Throwable) {
    }
}
