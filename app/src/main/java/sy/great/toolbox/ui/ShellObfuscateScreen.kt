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
import sy.great.toolbox.tools.ExportUtils
import sy.great.toolbox.tools.FileImporter
import sy.great.toolbox.tools.ShellObfuscator
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.Good
import sy.great.toolbox.ui.theme.LocalPalette
import sy.great.toolbox.ui.theme.Warn
import java.io.File

/**
 * Shell 脚本极端混淆页（ShellVMP V6）。
 *
 * 全程无需 root / ADB：
 *   · 内置 bash + coreutils + openssl 全部解压到 App 私有目录运行
 *   · 用户只需选一个 .sh，选强度，点生成
 */
@Composable
fun ShellObfuscateScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()

    var imported by remember { mutableStateOf<FileImporter.Imported?>(null) }
    var level by remember { mutableStateOf(ShellObfuscator.Level.Strong) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusBad by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var outInfo by remember { mutableStateOf("") }

    // 首次进入检查内置环境
    var envOk by remember { mutableStateOf<Boolean?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val err = withContext(Dispatchers.IO) { ShellObfuscator.selfCheck(ctx) }
        envOk = err.isEmpty()
        if (err.isNotEmpty()) {
            status = "内置环境自检未通过\n" + err
            statusBad = true
        } else {
            status = "环境就绪（bash + 依赖工具 + V6 均正常）"
            statusBad = false
        }
    }

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
                outInfo = ""
                logText = ""
            }
        }
    }

    fun runObfuscate() {
        val imp = imported ?: run {
            status = "请先选择 .sh 文件"
            statusBad = true
            return
        }
        busy = true
        status = "正在混淆（极端档可能需几十秒）..."
        statusBad = false
        logText = ""
        outInfo = ""

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val outDir = File(ctx.filesDir, "obf_out")
                ShellObfuscator.obfuscate(
                    ctx = ctx,
                    input = File(imp.localPath),
                    outDir = outDir,
                    level = level
                ) { line -> logText = logText + line + "\n" }
            }
            busy = false
            if (!result.ok) {
                status = result.message
                statusBad = true
                return@launch
            }

            // 导出到 Download
            val exp = withContext(Dispatchers.IO) {
                val bytes = File(result.outputPath).readBytes()
                val base = imp.displayName.substringBeforeLast('.', imp.displayName)
                ExportUtils.exportBytes(
                    ctx, "$base.protected.sh", bytes, "text/x-shellscript"
                )
            }

            status = if (exp.ok) {
                "完成：${fmtSize(result.sizeBytes)}\n已保存到 ${exp.displayPath}"
            } else {
                "生成成功但导出失败：" + exp.message
            }
            statusBad = !exp.ok
            outInfo = "产物：" + result.outputPath
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
        PageHeader(title = "Shell 混淆", onBack = onBack)
        Spacer(Modifier.height(10.dp))

        Text(
            "把 bash 脚本变成自保护产物：分块 AES-256 加密 + 密钥链 +\n" +
            "垃圾块 + 诱饵块，磁盘上不存完整明文。",
            fontSize = 12.sp, color = pal.inkSoft, lineHeight = 18.sp
        )

        Spacer(Modifier.height(14.dp))

        // ---- 选择文件 ----
        Card(pal) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("源脚本", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink)
                    Text(
                        imported?.displayName ?: "未选择（仅支持 .sh）",
                        fontSize = 11.sp, color = pal.inkSoft,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(10.dp))
                Chip("选择", enabled = !busy) {
                    // 用 "*/*"：与 ELF 逆向保持一致。
                    // 传 "text/x-shellscript" 这类 MIME，很多 ROM 的文件选择器
                    // 根本认不出 .sh，结果把所有脚本都灰掉、根本选不了。
                    // 放宽到全部类型，选完在代码里自己校验。
                    picker.launch(arrayOf("*/*"))
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 强度 ----
        Card(pal) {
            Text("混淆强度", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (lv in ShellObfuscator.Level.values()) {
                    Chip(lv.display, selected = level == lv, enabled = !busy) { level = lv }
                }
            }
            Spacer(Modifier.height(10.dp))
            val desc = when (level) {
                ShellObfuscator.Level.Normal -> "基础加密，体积最小，启动最快。"
                ShellObfuscator.Level.Strong -> "加垃圾块 + 诱饵块，体积与启动时间适中。"
                ShellObfuscator.Level.Extreme -> "最大垃圾/诱饵密度 + PBKDF2 90 万轮，启动明显变慢。"
            }
            Text(desc, fontSize = 11.sp, color = pal.inkSoft, lineHeight = 17.sp)
        }

        Spacer(Modifier.height(14.dp))

        // ---- 执行 ----
        Row(Modifier.fillMaxWidth()) {
            Chip(
                text = if (busy) "处理中..." else "开始混淆",
                enabled = !busy && imported != null && envOk != false,
                primary = true
            ) { runObfuscate() }
        }

        if (status.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                status, fontSize = 12.sp,
                color = if (statusBad) Bad else Good,
                lineHeight = 18.sp
            )
        }

        if (logText.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Card(pal) {
                Text("过程日志", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = pal.ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    logText.takeLast(2000),
                    fontSize = 10.sp, color = pal.inkSoft,
                    fontFamily = FontFamily.Monospace, lineHeight = 14.sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 限制说明 ----
        Card(pal) {
            Text("说明", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "· 产物 shebang 是 #!/bin/sh，Android 原生 mksh 即可运行\n" +
                "· 但产物依赖运行环境里有 openssl/gzip/sha256sum/sha512sum，\n" +
                "  普通手机没有，需要在 Termux 或 PC 上跑\n" +
                "· 仅支持 bash 语法的脚本，POSIX 脚本请先调整\n" +
                "· 脚本里不要用 set -e / set -x（会触发反调试指纹）\n" +
                "· 混淆过程完全离线，不需要 root / ADB / 网络",
                fontSize = 11.sp, color = pal.inkSoft, lineHeight = 17.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "内置 ShellVMP V6（AGPL-3.0）。闭源商用需向原作者获得授权。",
                fontSize = 10.sp, color = Warn, lineHeight = 15.sp
            )
        }

        Spacer(Modifier.height(120.dp))
    }
}

@Composable
private fun Card(pal: sy.great.toolbox.ui.theme.Palette, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.paperSoft, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) { content() }
}

@Composable
private fun Chip(
    text: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    val pal = LocalPalette.current
    val bg = when {
        !enabled -> pal.line
        primary -> Accent
        selected -> Accent
        else -> pal.paperSoft
    }
    val fg = when {
        !enabled -> pal.inkSoft
        primary || selected -> Color.White
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

private fun fmtSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> String.format("%.2f MB", bytes / 1024.0 / 1024.0)
}
