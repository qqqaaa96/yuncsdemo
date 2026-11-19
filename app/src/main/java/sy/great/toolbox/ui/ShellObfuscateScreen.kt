package sy.great.toolbox.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.runtime.LaunchedEffect
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
import sy.great.toolbox.tools.ArchiveExtractor
import sy.great.toolbox.tools.ExportUtils
import sy.great.toolbox.tools.FileImporter
import sy.great.toolbox.tools.NdkEnv
import sy.great.toolbox.tools.ShellObfuscator
import sy.great.toolbox.tools.SscCompiler
import sy.great.toolbox.ui.bottombar.FloatingBottomBar
import sy.great.toolbox.ui.bottombar.FloatingBottomBarItem
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.Good
import sy.great.toolbox.ui.theme.LocalPalette
import sy.great.toolbox.ui.theme.Palette
import sy.great.toolbox.ui.theme.Warn
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import java.io.File

/**
 * Shell 混淆页。
 *
 * 两个模式（底部 tab 切换）：
 *   1. ShellVMP —— 现有逻辑（脚本加密混淆，输出 .protected.sh）
 *   2. SSC      —— 编译成 ELF 可执行文件（需本地 NDK）
 *
 * 全程无需 root / ADB。
 */
@Composable
fun ShellObfuscateScreen(onBack: () -> Unit) {
    var mode by remember { mutableStateOf(0) }

    // 与主界面同款的液态玻璃底栏
    val tabNames = listOf("ShellVMP", "SSC")
    val backdrop = rememberLayerBackdrop {
        drawRect(LocalPalette.current.paper)
        drawContent()
    }

    Box(Modifier.fillMaxSize()) {
        // 内容层：作为玻璃的采样源
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
            when (mode) {
                0 -> ShellVmpTab(onBack = onBack)
                else -> SscTab(onBack = onBack)
            }
        }

        // 浮层：液态玻璃底栏
        FloatingBottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 28.dp, end = 28.dp, bottom = 20.dp),
            selectedIndex = mode,
            onSelected = { mode = it },
            backdrop = backdrop,
            tabsCount = tabNames.size,
            isBlurEnabled = true,
        ) { activateTab ->
            tabNames.forEachIndexed { index, name ->
                FloatingBottomBarItem(
                    selected = mode == index,
                    onClick = { activateTab(index) },
                    modifier = Modifier.defaultMinSize(minWidth = 86.dp),
                ) {
                    Text(
                        name,
                        fontSize = 13.sp,
                        fontWeight = if (mode == index) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * ShellVMP 原有页面（逻辑未改造，仅重命名为 Tab）。
 */
@Composable
private fun ShellVmpTab(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()

    var imported by remember { mutableStateOf<FileImporter.Imported?>(null) }
    var level by remember { mutableStateOf(ShellObfuscator.Level.Strong) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusBad by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var envOk by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        val err = withContext(Dispatchers.IO) { ShellObfuscator.selfCheck(ctx) }
        envOk = err.isEmpty()
        if (err.isEmpty()) {
            status = "环境就绪"
            statusBad = false
        } else {
            status = "环境自检未通过\n" + err
            statusBad = true
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
                status = "导入失败"
                statusBad = true
            } else {
                imported = imp
                status = "已选择：" + imp.displayName
                statusBad = false
                logText = ""
            }
        }
    }

    fun runObfuscate() {
        val imp = imported
        if (imp == null) {
            status = "请先选择 .sh 文件"
            statusBad = true
            return
        }
        busy = true
        status = "正在混淆..."
        statusBad = false
        logText = ""

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

            val exp = withContext(Dispatchers.IO) {
                val bytes = File(result.outputPath).readBytes()
                val base = imp.displayName.substringBeforeLast('.', imp.displayName)
                ExportUtils.exportBytes(ctx, base + ".protected.sh", bytes, "text/x-shellscript")
            }

            if (exp.ok) {
                status = "完成：" + fmtSize(result.sizeBytes) + "\n已保存到 " + exp.displayPath
                statusBad = false
            } else {
                status = "生成成功但导出失败：" + exp.message
                statusBad = true
            }
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
            "把 bash 脚本变成自保护产物：分块 AES-256 加密 + 密钥链 + 垃圾块 + 诱饵块。",
            fontSize = 12.sp,
            color = pal.inkSoft,
            lineHeight = 18.sp
        )

        Spacer(Modifier.height(14.dp))

        BoxCard(pal) {
            SourceRow(pal, imported, busy) { picker.launch(arrayOf("*/*")) }
        }

        Spacer(Modifier.height(12.dp))

        BoxCard(pal) {
            LevelPicker(pal, level, busy) { level = it }
        }

        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth()) {
            ActionChip(
                text = if (busy) "处理中..." else "开始混淆",
                enabled = !busy && imported != null && envOk != false,
                primary = true,
                onClick = { runObfuscate() }
            )
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

        if (logText.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            BoxCard(pal) {
                Text("过程日志", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = pal.ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    logText.takeLast(2000),
                    fontSize = 10.sp,
                    color = pal.inkSoft,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 14.sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        BoxCard(pal) {
            Text("说明", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "· 产物 shebang 是 #!/bin/sh，Android 原生 mksh 即可运行\n" +
                "· 但产物依赖 openssl/gzip/sha256sum/sha512sum，普通手机没有，\n" +
                "  需要在 Termux 或 PC 上运行\n" +
                "· 仅支持 bash 语法的脚本\n" +
                "· 不要用 set -e / set -x（会触发反调试指纹）\n" +
                "· 混淆过程完全离线，不需要 root / ADB / 网络",
                fontSize = 11.sp,
                color = pal.inkSoft,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "内置 ShellVMP V6（AGPL-3.0）。闭源商用需向原作者获得授权。",
                fontSize = 10.sp,
                color = Warn,
                lineHeight = 15.sp
            )
        }

        // 底部留白：给液态玻璃底栏腾位置（底栏高约 60dp + 外边距）
        Spacer(Modifier.height(110.dp))
    }
}

// ---------------- 子组件 ----------------

/**
 * SSC 编译 Tab。
 *
 * 流程：
 *   1. 进页检测 NDK 是否就绪
 *   2. 未就绪 → 弹窗提示需要上传压缩包
 *   3. 上传后自动解压，带进度弹窗
 *   4. 就绪后选脚本 → 编译 → 导出 ELF
 */
@Composable
private fun SscTab(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()

    var ndk by remember { mutableStateOf<NdkEnv.Status?>(null) }
    var script by remember { mutableStateOf<FileImporter.Imported?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusBad by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }

    // 上传/解压弹窗
    val showPickDialog = remember { mutableStateOf(false) }
    val showExtractDialog = remember { mutableStateOf(false) }
    var extractProgress by remember { mutableStateOf(ArchiveExtractor.Progress()) }
    var extractLines by remember { mutableStateOf(listOf<String>()) }

    // 进页检测环境
    LaunchedEffect(Unit) {
        val st = withContext(Dispatchers.IO) { NdkEnv.check(ctx) }
        ndk = st
        if (!st.ready) {
            status = st.reason
            statusBad = true
            showPickDialog.value = true
        } else {
            status = "环境就绪"
            statusBad = false
        }
    }

    // ---- NDK 压缩包选择器 ----
    val ndkPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        showPickDialog.value = false
        showExtractDialog.value = true
        extractProgress = ArchiveExtractor.Progress(stage = "准备中")
        extractLines = listOf("开始导入压缩包...")

        scope.launch {
            // 1. 先把压缩包拷到私有目录（大文件拷得慢，也要显示进度）
            val local = withContext(Dispatchers.IO) { FileImporter.importFromUri(ctx, uri) }
            if (local == null) {
                showExtractDialog.value = false
                status = "导入压缩包失败"
                statusBad = true
                return@launch
            }

            // 检查格式
            if (!ArchiveExtractor.isSupported(local.displayName)) {
                showExtractDialog.value = false
                status = "不支持的格式：${local.displayName}\n请使用 .zip / .tgz / .tar.gz"
                statusBad = true
                return@launch
            }

            extractLines = extractLines + "已导入：${local.displayName}（${fmtBytes(local.size)}）"
            extractProgress = ArchiveExtractor.Progress(stage = "正在解压")

            // 2. 解压
            val res = withContext(Dispatchers.IO) {
                ArchiveExtractor.extract(
                    archive = File(local.localPath),
                    destDir = NdkEnv.rootDir(ctx),
                ) { p ->
                    extractProgress = p
                    if (p.currentFile.isNotBlank()) {
                        extractLines = (extractLines + p.currentFile).takeLast(40)
                    }
                }
            }

            showExtractDialog.value = false

            if (!res.ok) {
                status = "解压失败：${res.message}"
                statusBad = true
                return@launch
            }

            extractLines = extractLines + "解压完成：${res.filesExtracted} 个文件，${fmtBytes(res.bytesExtracted)}"

            // 3. 重新检测
            val st = withContext(Dispatchers.IO) { NdkEnv.check(ctx) }
            ndk = st
            if (st.ready) {
                status = "环境就绪\nclang：${st.clangPath}"
                statusBad = false
            } else {
                status = "解压完成但环境仍不可用：${st.reason}"
                statusBad = true
            }
        }
    }

    // ---- 脚本选择器 ----
    val scriptPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "正在导入脚本..."
        scope.launch {
            val imp = withContext(Dispatchers.IO) { FileImporter.importFromUri(ctx, uri) }
            busy = false
            if (imp == null) {
                status = "导入失败"
                statusBad = true
            } else {
                script = imp
                status = "已选择：${imp.displayName}"
                statusBad = false
                logText = ""
            }
        }
    }

    fun doCompile() {
        val sc = script
        if (sc == null) {
            status = "请先选择 .sh 脚本"
            statusBad = true
            return
        }
        val st = ndk
        if (st == null || !st.ready) {
            showPickDialog.value = true
            return
        }

        busy = true
        status = "正在编译..."
        statusBad = false
        logText = ""

        scope.launch {
            val res = withContext(Dispatchers.IO) {
                SscCompiler.compile(
                    ctx = ctx,
                    script = File(sc.localPath),
                    outDir = File(ctx.filesDir, "ssc_out"),
                ) { line ->
                    logText = (logText + line + "\n")
                }
            }
            busy = false

            if (!res.ok) {
                status = res.message
                statusBad = true
                return@launch
            }

            // 导出 ELF
            val exp = withContext(Dispatchers.IO) {
                val bytes = File(res.outputPath).readBytes()
                val base = sc.displayName.substringBeforeLast('.', sc.displayName)
                ExportUtils.exportBytes(
                    ctx, base + ".elf", bytes, "application/octet-stream"
                )
            }

            if (exp.ok) {
                status = "编译成功：${fmtBytes(res.sizeBytes)}\n已保存到 ${exp.displayPath}"
                statusBad = false
            } else {
                status = "编译成功但导出失败：${exp.message}"
                statusBad = true
            }
        }
    }

    // ============ UI ============

    // 未就绪提示弹窗
    if (showPickDialog.value) {
        sy.great.toolbox.ui.component.ConfirmDialogMiuix(
            title = "需要编译工具链",
            content = "未检测到可用的 NDK。\n\n" +
                "请上传 NDK 压缩包（建议使用支持 aarch64 的版本）。\n" +
                "支持的格式：.zip / .tgz / .tar.gz\n\n" +
                "一般情况下，您下载的 NDK 可能为 .tgz 后缀。\n" +
                "解压位置：应用私有目录（无需任何存储权限）",
            confirmText = "选择压缩包",
            dismissText = "稍后",
            onConfirm = {
                showPickDialog.value = false
                ndkPicker.launch(arrayOf("*/*"))
            },
            onDismiss = { showPickDialog.value = false },
            showDialog = showPickDialog,
        )
    }

    // 解压进度弹窗
    if (showExtractDialog.value) {
        sy.great.toolbox.ui.component.ConfirmDialogMiuix(
            title = "正在解压 NDK",
            content = buildString {
                append(extractProgress.stage)
                append("\n")
                if (extractProgress.fraction >= 0f) {
                    append("进度 ").append((extractProgress.fraction * 100).toInt()).append("%\n")
                }
                append("已解压 ").append(extractProgress.filesDone).append(" 个文件\n")
                append(fmtBytes(extractProgress.bytesWritten))
                append("\n\n")
                // 列出最近解压的文件
                append(extractLines.takeLast(6).joinToString("\n"))
            },
            confirmText = "后台继续",
            dismissText = null,
            onConfirm = { showExtractDialog.value = false },
            onDismiss = { },
            showDialog = showExtractDialog,
            downloading = true,
            downloadProgress = if (extractProgress.fraction >= 0f) extractProgress.fraction else 0f,
            downloadMessage = extractProgress.stage,
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(pal.paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        PageHeader(title = "SSC 编译", onBack = onBack)
        Spacer(Modifier.height(10.dp))

        Text(
            "把 Shell 脚本编译成可执行 ELF。脚本内容加密内嵌，静态分析看不到明文。",
            fontSize = 12.sp,
            color = pal.inkSoft,
            lineHeight = 18.sp
        )

        Spacer(Modifier.height(14.dp))

        // NDK 状态
        BoxCard(pal) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ready = ndk?.ready == true
                Box(
                    Modifier
                        .height(10.dp).width(10.dp)
                        .background(if (ready) Good else Warn, RoundedCornerShape(5.dp))
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (ready) "NDK 已就绪" else "未检测到 NDK",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (ready) Good else Warn
                    )
                    ndk?.let {
                        if (it.ready) {
                            Text(
                                fmtBytes(NdkEnv.usedBytes(ctx)) + " · " + it.clangPath.substringAfterLast('/'),
                                fontSize = 10.sp, color = pal.inkSoft
                            )
                        }
                    }
                }
                Text(
                    if (ready) "重新上传" else "上传",
                    fontSize = 11.5.sp,
                    color = Accent,
                    modifier = Modifier
                        .pressable(pressedScale = 0.94f) { ndkPicker.launch(arrayOf("*/*")) }
                        .padding(6.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 脚本选择
        BoxCard(pal) {
            SourceRow(pal, script, busy) { scriptPicker.launch(arrayOf("*/*")) }
        }

        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth()) {
            ActionChip(
                text = if (busy) "编译中..." else "开始编译",
                enabled = !busy && script != null && ndk?.ready == true,
                primary = true,
                onClick = { doCompile() }
            )
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

        if (logText.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            BoxCard(pal) {
                Text("编译日志", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = pal.ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    logText.takeLast(3000),
                    fontSize = 10.sp,
                    color = pal.inkSoft,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 14.sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        BoxCard(pal) {
            Text("说明", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "· 产物是 ARM64 PIE ELF，可直接在 Android 运行\n" +
                    "· 脚本内容加密内嵌在 ELF 里，静态分析看不到明文\n" +
                    "· 运行时需要系统自带 /system/bin/sh（所有 Android 都有）\n" +
                    "· NDK 解压在应用私有目录，不需要 root / ADB / 存储权限\n" +
                    "· 支持的压缩包：.zip / .tgz / .tar.gz",
                fontSize = 11.sp,
                color = pal.inkSoft,
                lineHeight = 17.sp
            )
        }

        // 底部留白：给液态玻璃底栏腾位置
        Spacer(Modifier.height(110.dp))
    }
}

/** 字节数格式化 */
private fun fmtBytes(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> "%.2f GB".format(b / 1024.0 / 1024 / 1024)
    b >= 1024L * 1024 -> "%.1f MB".format(b / 1024.0 / 1024)
    b >= 1024L -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

@Composable
private fun BoxCard(pal: Palette, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.paperSoft, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        content()
    }
}

@Composable
private fun SourceRow(
    pal: Palette,
    imported: FileImporter.Imported?,
    busy: Boolean,
    onPick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text("源脚本", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink)
            Text(
                imported?.displayName ?: "未选择",
                fontSize = 11.sp,
                color = pal.inkSoft,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(10.dp))
        ActionChip("选择", enabled = !busy, onClick = onPick)
    }
}

@Composable
private fun LevelPicker(
    pal: Palette,
    level: ShellObfuscator.Level,
    busy: Boolean,
    onPick: (ShellObfuscator.Level) -> Unit
) {
    Text("混淆强度", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (lv in ShellObfuscator.Level.values()) {
            ActionChip(
                text = lv.display,
                selected = level == lv,
                enabled = !busy,
                onClick = { onPick(lv) }
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    val desc = when (level) {
        ShellObfuscator.Level.Normal -> "基础加密，体积最小，启动最快。"
        ShellObfuscator.Level.Strong -> "加垃圾块 + 诱饵块，体积与启动时间适中。"
        ShellObfuscator.Level.Extreme -> "最大垃圾/诱饵密度 + PBKDF2 90 万轮。"
    }
    Text(desc, fontSize = 11.sp, color = pal.inkSoft, lineHeight = 17.sp)
}

@Composable
private fun ActionChip(
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

private fun fmtSize(bytes: Long): String {
    return when {
        bytes < 1024L -> bytes.toString() + " B"
        bytes < 1048576L -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.2f MB", bytes / 1048576.0)
    }
}
