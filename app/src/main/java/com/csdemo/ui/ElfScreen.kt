package com.csdemo.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.tools.CfgBuilder
import com.csdemo.tools.ElfParser
import com.csdemo.tools.ExportUtils
import com.csdemo.tools.decompiler.DecompilerEngine
import com.csdemo.tools.ElfPatcher
import com.csdemo.tools.FileImporter
import com.csdemo.tools.PseudoCode
import com.csdemo.ui.theme.Accent
import com.csdemo.ui.theme.Bad
import com.csdemo.ui.theme.Good
import com.csdemo.ui.theme.Ink
import com.csdemo.ui.theme.InkFaint
import com.csdemo.ui.theme.InkSoft
import com.csdemo.ui.theme.Line
import com.csdemo.ui.theme.Paper
import com.csdemo.ui.theme.PaperSoft
import com.csdemo.ui.theme.Warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ElfScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var imported by remember { mutableStateOf<FileImporter.Imported?>(null) }
    var elf by remember { mutableStateOf<ElfParser.Elf?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var manualPath by remember { mutableStateOf("") }

    // SAF 文件选择器
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        error = ""
        scope.launch {
            val imp = withContext(Dispatchers.IO) { FileImporter.importFromUri(ctx, uri) }
            if (imp == null) {
                error = "导入失败（无法读取该文件）"
                loading = false
                return@launch
            }
            imported = imp
            val parsed = withContext(Dispatchers.IO) { parseFile(imp.localPath) }
            loading = false
            when (parsed) {
                is ParseOutcome.Ok -> elf = parsed.elf
                is ParseOutcome.Fail -> error = parsed.msg
            }
        }
    }

    // 已加载 ELF → 显示详情
    val e = elf
    if (e != null) {
        ElfDetail(
            elf0 = e,
            name0 = imported?.displayName ?: "未知",
            onBack = {
                elf = null
                imported = null
                error = ""
            }
        )
        return
    }

    // 未加载 → 上传页
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("ELF 逆向")
        Spacer(Modifier.height(6.dp))
        Text("上传 .so / 可执行文件，查看反汇编、控制流图与符号",
            fontSize = 11.sp, color = InkSoft, lineHeight = 16.sp)
        Spacer(Modifier.height(16.dp))

        // 主入口：上传
        Column(
            Modifier
                .fillMaxWidth()
                .pressable(pressedScale = 0.98f, enabled = !loading) {
                    picker.launch(arrayOf("*/*"))
                }
                .border(1.dp, Line, RoundedCornerShape(14.dp))
                .background(PaperSoft, RoundedCornerShape(14.dp))
                .padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("⬆", fontSize = 26.sp, color = Accent)
            Spacer(Modifier.height(8.dp))
            Text("点击选择文件", fontSize = 14.sp,
                fontWeight = FontWeight.Medium, color = Ink)
            Spacer(Modifier.height(4.dp))
            Text("推荐 .so / .elf / .bin", fontSize = 11.sp, color = InkSoft)
        }

        if (loading) {
            Spacer(Modifier.height(14.dp))
            Card(null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 14.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在解析...", fontSize = 12.sp, color = InkSoft)
                }
                Spacer(Modifier.height(10.dp))
                ThinProgress(Modifier.fillMaxWidth().height(2.dp))
            }
        }

        if (error.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Card(null) {
                Row(verticalAlignment = Alignment.Top) {
                    Text("✕", fontSize = 13.sp, color = Bad)
                    Spacer(Modifier.width(8.dp))
                    Text(error, fontSize = 12.sp, color = Bad, lineHeight = 17.sp)
                }
            }
        }

        // 手动路径（高级）
        Spacer(Modifier.height(18.dp))
        Card("或手动输入路径") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = manualPath,
                        onValueChange = { manualPath = it },
                        placeholder = { Text("/data/local/tmp/lib.so", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val p = manualPath.trim()
                        if (p.isBlank()) {
                            error = "请输入路径"
                            return@Button
                        }
                        loading = true
                        error = ""
                        scope.launch {
                            val imp = withContext(Dispatchers.IO) { FileImporter.importFromPath(p) }
                            if (imp == null) {
                                error = "无法读取：文件不存在或无权限"
                                loading = false
                                return@launch
                            }
                            imported = imp
                            val parsed = withContext(Dispatchers.IO) { parseFile(imp.localPath) }
                            loading = false
                            when (parsed) {
                                is ParseOutcome.Ok -> elf = parsed.elf
                                is ParseOutcome.Fail -> error = parsed.msg
                            }
                        }
                    },
                    enabled = !loading
                ) { Text("打开") }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

/** 解析结果 */
private sealed class ParseOutcome {
    data class Ok(val elf: ElfParser.Elf) : ParseOutcome()
    data class Fail(val msg: String) : ParseOutcome()
}

private fun parseFile(path: String): ParseOutcome {
    val bytes = FileImporter.read(path)
        ?: return ParseOutcome.Fail("读取失败：文件不存在或权限不足")
    if (bytes.size < 64) {
        return ParseOutcome.Fail("文件太小（" + bytes.size + " 字节），不是有效 ELF")
    }
    // 检查 magic
    if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
        bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()) {
        return ParseOutcome.Fail(
            "不是 ELF 文件（magic = " +
                    String.format("%02X %02X %02X %02X", bytes[0], bytes[1], bytes[2], bytes[3]) + "）"
        )
    }
    return try {
        ParseOutcome.Ok(ElfParser.parse(bytes))
    } catch (ex: Exception) {
        ParseOutcome.Fail("ELF 解析失败：" + (ex.message ?: "未知错误"))
    }
}

// ============================================================
// ELF 详情页
// ============================================================

@Composable
private fun ElfDetail(elf0: ElfParser.Elf, name0: String, onBack: () -> Unit) {
    // 内部状态：字符串修改后会替换为新的 ELF
    var elf by remember(elf0) { mutableStateOf(elf0) }
    var name by remember(name0) { mutableStateOf(name0) }
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("概览", "函数", "节区", "符号", "字符串", "控制流", "伪 C")
    val tabScroll = rememberScrollState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 顶栏（返回键为绘制的箭头）
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackArrow(onClick = onBack)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink,
                    maxLines = 1)
                Text(elf.machineName + "  ·  " + elf.typeName,
                    fontSize = 10.sp, color = InkSoft)
            }
        }
        Spacer(Modifier.height(10.dp))

        // 可横向滑动的 tab 栏
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(tabScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabs.forEachIndexed { i, t ->
                TabChip(t, selected = i == tab, onClick = { tab = i })
            }
        }
        Spacer(Modifier.height(12.dp))

        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ElfOverview(elf)
                1 -> ElfFunctions(elf)
                2 -> ElfSections(elf)
                3 -> ElfSymbols(elf)
                4 -> ElfStrings(
                    elf = elf,
                    srcName = name,
                    onElfUpdated = { newElf, newName ->
                        elf = newElf
                        name = newName
                    }
                )
                5 -> WholeElfCfg(elf, name)
                else -> WholeElfPseudoC(elf, name)
            }
        }
    }
}

// ============================================================
// 整个 ELF 的控制流（汇总视图）
// ============================================================

@Composable
private fun WholeElfCfg(elf: ElfParser.Elf, fileName: String) {
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(0) } // 0=分析中 1=完成 2=失败
    var progress by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf("") }
    var graph by remember { mutableStateOf<CfgBuilder.Graph?>(null) }

    val funcs = remember(elf) {
        CfgBuilder.discoverFunctions(elf).filter { it.hasValidAddr }
    }

    // 进页面立即自动分析
    androidx.compose.runtime.LaunchedEffect(elf) {
        phase = 0
        progress = 0
        total = funcs.size
        val g = withContext(Dispatchers.Default) {
            CfgBuilder.buildWhole(
                elf = elf,
                maxFunctions = 400,
                maxInsnsPerFunc = 900,
                onProgress = { done, tot ->
                    progress = done
                    total = tot
                }
            )
        }
        if (g.blocks.isEmpty()) {
            phase = 2
            // 失败时输出结构诊断，让用户知道到底是哪一步不行
            val segs = elf.executableSegments()
            val hasSymtab = elf.sections.any { it.name == ".symtab" }
            val hasDynsym = elf.sections.any { it.name == ".dynsym" }
            val archOk = elf.machine == 0xB7 || elf.machine == 0x28

            val sb = StringBuilder()
            sb.append("未构建出任何基本块\n\n")
            sb.append("ELF 类型：").append(elf.typeName).append('\n')
            sb.append("架构：").append(elf.machineName).append('\n')
            sb.append("入口：0x").append(java.lang.Long.toHexString(elf.entry)).append('\n')
            sb.append("可执行段：").append(segs.size).append('\n')
            sb.append(".symtab：").append(if (hasSymtab) "有" else "无").append('\n')
            sb.append(".dynsym：").append(if (hasDynsym) "有" else "无").append('\n')
            sb.append("已发现入口：").append(funcs.size).append('\n')
            sb.append('\n')
            sb.append(
                when {
                    segs.isEmpty() ->
                        "原因：没有找到带执行权限的段（PT_LOAD + PF_X）。"
                    !archOk ->
                        "原因：当前只支持 AArch64 / ARM 反汇编，该文件架构不匹配。"
                    funcs.isEmpty() ->
                        "原因：未发现任何函数入口（无符号表且入口不在可执行段内）。"
                    else ->
                        "原因：已定位到入口，但解码未产生指令（可能是数据文件）。"
                }
            )
            msg = sb.toString()
        } else {
            graph = g
            phase = 1
        }
    }

    // 分析中：全屏进度
    if (phase == 0) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spinner(size = 34.dp, color = Accent)
            Spacer(Modifier.height(20.dp))
            Text(
                "正在构建控制流图",
                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (total > 0) "已分析 $progress / $total 个函数" else "正在准备...",
                fontSize = 12.5.sp, color = InkSoft
            )
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth(0.7f)) {
                ThinProgress(Modifier.fillMaxWidth().height(3.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "正在逐个函数解析指令并划分基本块\n" +
                        "函数较多时需要一些时间，请耐心等待",
                fontSize = 11.sp, color = InkFaint,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 17.sp
            )
        }
        return
    }

    // 失败
    if (phase == 2) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("✕", fontSize = 30.sp, color = Bad)
            Spacer(Modifier.height(14.dp))
            Text(msg, fontSize = 13.sp, color = Bad,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 19.sp)
        }
        return
    }

    // 完成：直接展示图
    val g = graph
    if (g != null) {
        CfgGraphScreen(
            graph = g,
            funcName = fileName + "　（整个文件）",
            onBack = { }
        )
    }
}

// ============================================================
// 整个 ELF 的伪 C（全量）
// ============================================================

@Composable
private fun WholeElfPseudoC(elf: ElfParser.Elf, fileName: String) {
    var phase by remember { mutableStateOf(0) } // 0=分析中 1=完成 2=失败
    var progress by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var funcCount by remember { mutableStateOf(0) }
    var blockCount by remember { mutableStateOf(0) }

    val funcs = remember(elf) {
        CfgBuilder.discoverFunctions(elf).filter { it.hasValidAddr }
    }

    androidx.compose.runtime.LaunchedEffect(elf) {
        phase = 0
        progress = 0
        total = funcs.size
        output = ""

        val result = withContext(Dispatchers.Default) {
            try {
                // 新引擎：ELF → IR → CFG → 伪 C
                DecompilerEngine.decompileWhole(
                    elf = elf,
                    maxFunctions = 300,
                    maxInsnsPerFunc = 1200,
                    onProgress = { done, tot ->
                        progress = done
                        total = tot
                    }
                )
            } catch (e: Exception) {
                DecompilerEngine.Result("", 0, 0, "分析异常：" + (e.message ?: "未知"))
            }
        }

        if (result.text.isBlank()) {
            phase = 2
            msg = if (result.error.isNotBlank()) result.error else {
                buildDiag(elf, funcs.size)
            }
        } else {
            output = result.text
            funcCount = result.functionCount
            blockCount = result.blockCount
            phase = 1
        }
    }

    // 分析中
    if (phase == 0) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spinner(size = 34.dp, color = Accent)
            Spacer(Modifier.height(20.dp))
            Text("正在生成伪 C", fontSize = 15.sp,
                fontWeight = FontWeight.Medium, color = Ink)
            Spacer(Modifier.height(8.dp))
            Text(
                if (total > 0) "已处理 $progress / $total 个函数" else "正在准备...",
                fontSize = 12.5.sp, color = InkSoft
            )
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth(0.7f)) {
                ThinProgress(Modifier.fillMaxWidth().height(3.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "将逐个函数划分基本块并还原分支结构\n函数较多时需要一些时间",
                fontSize = 11.sp, color = InkFaint,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 17.sp
            )
        }
        return
    }

    // 失败
    if (phase == 2) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("✕", fontSize = 30.sp, color = Bad)
            Spacer(Modifier.height(14.dp))
            Text(msg, fontSize = 12.sp, color = Bad,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 18.sp)
        }
        return
    }

    // 完成
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportMsg by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        // 说明条 + 导出
        Box(
            Modifier
                .fillMaxWidth()
                .background(PaperSoft, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            Column {
                Text(
                    "由 IR 重建：寄存器已映射为变量，分支已还原为 if / while。\n" +
                            "不做类型推导，变量名不代表真实语义。\n" +
                            "函数 $funcCount 个",
                    fontSize = 10.5.sp, color = InkSoft, lineHeight = 15.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (exporting) return@Button
                            exporting = true
                            exportMsg = ""
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    ExportUtils.exportText(
                                        ctx = ctx,
                                        fileName = baseName(fileName) + "_pseudoc.c",
                                        content = output,
                                        mime = "text/x-c"
                                    )
                                }
                                exportMsg = if (r.ok) {
                                    "已导出\n" + r.displayPath
                                } else {
                                    "导出失败：" + r.message
                                }
                                exporting = false
                            }
                        },
                        enabled = !exporting
                    ) {
                        Text(if (exporting) "导出中..." else "导出 .c", fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    if (exportMsg.isNotBlank()) {
                        Text(
                            exportMsg,
                            fontSize = 10.5.sp,
                            color = if (exportMsg.startsWith("已导出")) Good else Bad,
                            lineHeight = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // 代码区（可滚动）
        Box(
            Modifier
                .fillMaxSize()
                .background(PaperSoft, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text(
                    output,
                    fontSize = 10.5.sp,
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/** 取不带扩展名的文件名 */
private fun baseName(name: String): String {
    val n = name.trim()
    if (n.isEmpty()) return "output"
    val dot = n.lastIndexOf('.')
    return if (dot > 0) n.substring(0, dot) else n
}

/** 结构诊断（与控制流页保持一致，方便定位） */
private fun buildDiag(elf: ElfParser.Elf, funcCount: Int): String {
    val segs = elf.executableSegments()
    val archOk = elf.machine == 0xB7 || elf.machine == 0x28
    val sb = StringBuilder()
    sb.append("未生成任何伪代码\n\n")
    sb.append("ELF 类型：").append(elf.typeName).append('\n')
    sb.append("架构：").append(elf.machineName).append('\n')
    sb.append("入口：0x").append(java.lang.Long.toHexString(elf.entry)).append('\n')
    sb.append("可执行段：").append(segs.size).append('\n')
    sb.append("已发现入口：").append(funcCount).append('\n')
    sb.append('\n')
    sb.append(
        when {
            segs.isEmpty() -> "原因：没有找到带执行权限的段（PT_LOAD + PF_X）。"
            !archOk -> "原因：当前只支持 AArch64 / ARM 反汇编，该文件架构不匹配。"
            funcCount == 0 -> "原因：未发现任何函数入口。"
            else -> "原因：已定位到入口，但解码未产生指令（可能是数据文件）。"
        }
    )
    return sb.toString()
}

/** 胶囊 tab，选中为蓝底白字（多个页面共用） */
@Composable
fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .pressable(pressedScale = 0.94f, onClick = onClick)
            .background(
                if (selected) Accent else PaperSoft,
                RoundedCornerShape(50)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 13.sp,
            color = if (selected) Paper else InkSoft,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@Composable
private fun ElfOverview(elf: ElfParser.Elf) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Card("文件头") {
            KV("Magic", "7F 45 4C 46")
            KV("位数", if (elf.is64) "64-bit" else "32-bit")
            KV("字节序", if (elf.isLittleEndian) "小端" else "大端")
            KV("架构", elf.machineName)
            KV("类型", elf.typeName)
            KV("入口", "0x" + java.lang.Long.toHexString(elf.entry), mono = true)
            KV("节区数", elf.shnum.toString())
            KV("程序头数", elf.phnum.toString())
        }
        Spacer(Modifier.height(12.dp))
        Card("统计") {
            KV("符号总数", elf.symbols.size.toString())
            KV("导入符号", elf.imports.size.toString())
            KV("函数数", elf.symbols.count { it.isFunc }.toString())
            KV("文件大小", fmtSize(elf.bytes.size.toLong()))
        }
        Spacer(Modifier.height(12.dp))
        Card("程序头") {
            if (elf.programs.isEmpty()) {
                Text("无", fontSize = 12.sp, color = InkSoft)
            }
            elf.programs.take(30).forEach { ph ->
                KV(
                    ph.typeName,
                    "vaddr=0x" + java.lang.Long.toHexString(ph.vaddr) +
                            "  size=0x" + java.lang.Long.toHexString(ph.filesz),
                    mono = true
                )
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ElfFunctions(elf: ElfParser.Elf) {
    val scope = rememberCoroutineScope()

    // 函数列表（可变，批量分析后更新）
    var funcs by remember(elf) { mutableStateOf(CfgBuilder.functions(elf)) }
    var selected by remember { mutableStateOf<CfgBuilder.FuncInfo?>(null) }
    var query by remember { mutableStateOf("") }
    var byComplexity by remember { mutableStateOf(false) }

    // 全量分析状态
    var analyzing by remember { mutableStateOf(false) }
    var analyzedN by remember { mutableStateOf(0) }
    var analyzeMsg by remember { mutableStateOf("") }
    var stopFlag by remember { mutableStateOf(false) }

    val sel = selected
    if (sel != null) {
        // 从最新列表里拿最新的（可能已带分析数据）
        val fresh = funcs.firstOrNull { it.addr == sel.addr && it.name == sel.name } ?: sel
        FuncDetail(elf, fresh, onBack = { selected = null })
        return
    }

    // 先反筛选，再排序
    val filtered = remember(funcs, query) {
        if (query.isBlank()) funcs
        else funcs.filter {
            it.name.contains(query, true) ||
                    java.lang.Long.toHexString(it.addr).contains(query, true)
        }
    }
    val shown = remember(filtered, byComplexity) {
        if (byComplexity) CfgBuilder.sortByComplexity(filtered)
        else CfgBuilder.sortForAnalysis(filtered)
    }

    val analyzedCount = funcs.count { it.analyzed }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索函数名 / 地址", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        // 工具栏（可横向滑动，防竖屏挤压）
        ScrollRow(spacing = 8.dp) {
            TabChip(
                if (analyzing) "分析中..." else "全量分析",
                selected = false,
                onClick = {
                    if (analyzing) return@TabChip
                    analyzing = true
                    analyzedN = 0
                    analyzeMsg = ""
                    stopFlag = false
                    scope.launch {
                        val total = funcs.size
                        val result = withContext(Dispatchers.Default) {
                            CfgBuilder.analyzeAll(
                                elf = elf,
                                funcs = funcs,
                                maxInsnsPerFunc = 2000,
                                onProgress = { done, _, _ ->
                                    // 降低跨线程状态写入频率（每 8 个更新一次）
                                    if (done % 8 == 0 || done == total) {
                                        analyzedN = done
                                    }
                                },
                                shouldStop = { stopFlag }
                            )
                        }
                        // 分块丢回 UI，避免大列表一次性重组卡顿
                        funcs = result
                        analyzing = false
                        analyzeMsg = if (stopFlag) "已取消（部分未分析）"
                        else "已分析 " + total + " 个函数"
                    }
                }
            )
            TabChip("按地址", selected = !byComplexity, onClick = { byComplexity = false })
            TabChip("按复杂度", selected = byComplexity, onClick = { byComplexity = true })
            if (analyzing) {
                TabChip("停止", selected = false, onClick = { stopFlag = true })
            }
        }

        if (analyzing) {
            Spacer(Modifier.height(8.dp))
            Card(null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 14.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "正在分析 " + analyzedN + " / " + funcs.size,
                        fontSize = 12.sp, color = InkSoft,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                ThinProgress(Modifier.fillMaxWidth().height(2.dp))
                Spacer(Modifier.height(6.dp))
                Text(
                    "全量分析会在后台构建所有函数的控制流图。列表会直接显示块数。",
                    fontSize = 10.sp, color = InkFaint, lineHeight = 15.sp
                )
            }
        } else if (analyzeMsg.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(analyzeMsg, fontSize = 11.sp, color = InkSoft)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "共 " + funcs.size + " 个，匹配 " + shown.size +
                    if (analyzedCount > 0) "（已分析 " + analyzedCount + "）" else "",
            fontSize = 11.sp, color = InkSoft
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.addr.toString() + it.name }) { f ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .pressable(pressedScale = 0.985f, onClick = { selected = f })
                        .padding(vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            f.name, fontSize = 13.sp, color = Ink,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        if (f.isImport) {
                            Text("导入", fontSize = 10.sp, color = Warn)
                        } else if (f.analyzed) {
                            // 块数徽标：越多颜色越深，方便一眼找到大函数
                            val col = when {
                                f.blockCount >= 60 -> Bad
                                f.blockCount >= 25 -> Warn
                                f.blockCount >= 8 -> Accent
                                else -> InkSoft
                            }
                            Text(
                                f.blockCount.toString() + " 块",
                                fontSize = 10.sp, color = col,
                                fontWeight = FontWeight.Medium
                            )
                        } else if (f.isExport) {
                            Text("导出", fontSize = 10.sp, color = Good)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "0x" + java.lang.Long.toHexString(f.addr) +
                                    (if (f.size > 0) "   size=" + f.size else ""),
                            fontSize = 10.sp, color = InkSoft,
                            fontFamily = FontFamily.Monospace
                        )
                        if (f.analyzed) {
                            Text(
                                "   指令 " + f.insnCount + " ・ 边 " + f.edgeCount,
                                fontSize = 10.sp, color = InkFaint
                            )
                        }
                    }
                }
                HLine()
            }
        }
    }
}

@Composable
private fun FuncDetail(elf: ElfParser.Elf, f: CfgBuilder.FuncInfo, onBack: () -> Unit) {
    var mode by remember { mutableStateOf(0) } // 0=汇编 1=CFG 2=伪码
    var showGraph by remember { mutableStateOf(false) }
    val graph = remember(f.addr, elf) { CfgBuilder.buildForFunc(elf, f, 3000) }
    val modeScroll = rememberScrollState()

    // 进入独立的 CFG 图形页面
    if (showGraph) {
        CfgGraphScreen(
            graph = graph,
            funcName = f.name,
            onBack = { showGraph = false }
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackArrow(onClick = onBack)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = Ink, maxLines = 1)
                Text(
                    "0x" + java.lang.Long.toHexString(f.addr) +
                            "　" + graph.blocks.size + " 块・" + graph.insnCount + " 指令・" +
                            graph.edges.size + " 边",
                    fontSize = 10.sp,
                    color = InkSoft, fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth().horizontalScroll(modeScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("汇编", "伪码").forEachIndexed { i, t ->
                TabChip(t, selected = i == mode, onClick = { mode = i })
            }
            // 独立图形页面入口
            TabChip("控制流图", selected = false, onClick = { showGraph = true })
        }
        Spacer(Modifier.height(10.dp))

        if (graph.blocks.isEmpty()) {
            Card(null) {
                Text(
                    if (f.isImport) "这是导入符号，本文件内没有函数体。"
                    else "无法构建控制流（可能是数据符号或地址越界）",
                    fontSize = 12.sp, color = InkSoft, lineHeight = 17.sp
                )
            }
            return
        }

        Box(Modifier.weight(1f)) {
            when (mode) {
                0 -> AsmList(graph)
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    Card(null) {
                        Text(
                            "这不是反编译，而是把汇编按基本块排列的结构化视图。\n" +
                                    "真正的反编译需要类型推导与数据流分析。",
                            fontSize = 10.sp, color = Warn, lineHeight = 15.sp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Card("结构化块视图") {
                        Mono(PseudoCode.generate(graph, f.name))
                    }
                    Spacer(Modifier.height(10.dp))
                    Card("C 风格改写") {
                        Mono(PseudoCode.pseudoView(graph))
                    }
                    Spacer(Modifier.height(30.dp))
                }
            }
        }
    }
}

@Composable
private fun AsmList(graph: CfgBuilder.Graph) {
    LazyColumn(Modifier.fillMaxSize()) {
        graph.blocks.forEach { b ->
            item(key = "blk_" + b.start) {
                Row(
                    Modifier.padding(top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(6.dp).background(
                            when {
                                b.isEntry -> Accent
                                b.isExit -> Bad
                                else -> InkFaint
                            }, CircleShape
                        )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "loc_" + java.lang.Long.toHexString(b.start).uppercase() +
                                (if (b.isEntry) "  [入口]" else if (b.isExit) "  [出口]" else ""),
                        fontSize = 10.sp, color = InkSoft,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            items(b.insns) { insn ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(
                        java.lang.Long.toHexString(insn.addr),
                        fontSize = 10.sp, color = InkFaint,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(58.dp)
                    )
                    Text(
                        insn.hex,
                        fontSize = 10.sp, color = InkFaint,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(62.dp)
                    )
                    Text(
                        insn.text,
                        fontSize = 11.sp,
                        color = when {
                            insn.isCall -> Good
                            insn.isBranch -> Accent
                            insn.isReturn -> Bad
                            else -> Ink
                        },
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ElfSections(elf: ElfParser.Elf) {
    var query by remember { mutableStateOf("") }
    val shown = remember(elf, query) {
        if (query.isBlank()) elf.sections
        else elf.sections.filter {
            it.name.contains(query, true) || it.typeName.contains(query, true)
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索节区名 / 类型", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text("共 " + elf.sections.size + " 个，匹配 " + shown.size + " 个",
            fontSize = 11.sp, color = InkSoft)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(shown) { s ->
                Column(Modifier.padding(vertical = 7.dp)) {
                    Row {
                        Text(
                            s.name.ifBlank { "(无名)" }, fontSize = 12.sp,
                            color = Ink, fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f), maxLines = 1
                        )
                        Text(s.typeName, fontSize = 10.sp, color = Accent)
                    }
                    Text(
                        "addr=0x" + java.lang.Long.toHexString(s.addr) +
                                "  off=0x" + java.lang.Long.toHexString(s.offset) +
                                "  size=" + s.size + "  " + s.perms,
                        fontSize = 10.sp, color = InkSoft,
                        fontFamily = FontFamily.Monospace
                    )
                }
                HLine()
            }
        }
    }
}

@Composable
private fun ElfSymbols(elf: ElfParser.Elf) {
    var query by remember { mutableStateOf("") }
    var onlyImport by remember { mutableStateOf(false) }
    val base = remember(elf) { elf.symbols.filter { it.name.isNotBlank() } }
    val shown = remember(base, query, onlyImport) {
        var list = base
        if (onlyImport) list = list.filter { it.isImport }
        if (query.isNotBlank()) {
            list = list.filter {
                it.name.contains(query, true) ||
                        java.lang.Long.toHexString(it.value).contains(query, true)
            }
        }
        list
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索符号名 / 地址", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "共 " + base.size + " 个，匹配 " + shown.size + "（导入 " + elf.imports.size + "）",
                fontSize = 11.sp, color = InkSoft,
                modifier = Modifier.weight(1f)
            )
            TabChip("仅导入", selected = onlyImport, onClick = { onlyImport = !onlyImport })
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(shown.take(3000)) { s ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontSize = 12.sp, color = Ink,
                            fontFamily = FontFamily.Monospace, maxLines = 1)
                        Text(
                            "0x" + java.lang.Long.toHexString(s.value) +
                                    "  " + s.typeName + "  " + s.bind,
                            fontSize = 10.sp, color = InkSoft
                        )
                    }
                    if (s.isImport) Text("IMP", fontSize = 9.sp, color = Warn)
                }
                HLine()
            }
        }
    }
}

@Composable
private fun ElfStrings(
    elf: ElfParser.Elf,
    srcName: String,
    onElfUpdated: (ElfParser.Elf, String) -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // 一次提取带定位信息的字符串
    val all = remember(elf) { ElfParser.extractLocatableStrings(elf, 4, 20000) }

    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ElfParser.LocString?>(null) }
    var editText by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    val shown = remember(all, query) {
        if (query.isBlank()) all
        else all.filter {
            it.text.contains(query, true) ||
                    java.lang.Long.toHexString(it.addr).contains(query, true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 搜索框
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索字符串 / 地址", fontSize = 13.sp) },
            singleLine = true,
            leadingIcon = { Text("⌕", fontSize = 15.sp, color = InkFaint) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    TextButton(onClick = { query = "" }) { Text("清空", fontSize = 11.sp) }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "共 " + all.size + " 条，匹配 " + shown.size + " 条·点击可修改",
            fontSize = 11.sp, color = InkSoft
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(shown.take(3000)) { s ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .pressable(pressedScale = 0.99f, onClick = {
                            editing = s
                            editText = s.text
                            msg = ""
                        })
                        .padding(vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            java.lang.Long.toHexString(s.addr),
                            fontSize = 10.sp, color = InkFaint,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(60.dp)
                        )
                        Text(
                            s.section,
                            fontSize = 9.sp, color = Accent,
                            modifier = Modifier.width(64.dp), maxLines = 1
                        )
                        Text(
                            "回" + s.maxLen,
                            fontSize = 9.sp, color = InkFaint
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        s.text, fontSize = 11.sp, color = Ink,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3
                    )
                }
                HLine()
            }
        }
    }

    // 编辑弹窗
    val target = editing
    if (target != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("修改字符串", fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        "地址 " + java.lang.Long.toHexString(target.addr) +
                                "　节区 " + target.section,
                        fontSize = 10.sp, color = InkFaint
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "槽位长度 " + target.maxLen + " 字节（新内容不能超过）",
                        fontSize = 10.sp, color = InkSoft
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        label = { Text("新内容", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    val len = editText.toByteArray(Charsets.UTF_8).size
                    val over = len > target.maxLen
                    Text(
                        len.toString() + " / " + target.maxLen + " 字节" +
                                (if (over) "　超长，不可写入" else "　可写入"),
                        fontSize = 11.sp,
                        color = if (over) Bad else Good
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "只能原地覆写：新内容短于原长度时，剩余字节填 NUL。" +
                                "不改指针、不改长度字段，不会破坏 ELF 结构。",
                        fontSize = 10.sp, color = InkSoft, lineHeight = 15.sp
                    )
                    if (msg.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(msg, fontSize = 11.sp, color = if (msg.startsWith("已")) Good else Bad,
                            lineHeight = 16.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        val newBytes = editText.toByteArray(Charsets.UTF_8)
                        if (newBytes.size > target.maxLen) {
                            msg = "新内容超长，无法原地覆写"
                            return@TextButton
                        }
                        saving = true
                        msg = ""
                        scope.launch {
                            val outFile = ElfPatcher.suggestOutputName(ctx, srcName)
                            val r = withContext(Dispatchers.IO) {
                                ElfPatcher.replaceString(elf.bytes, target, editText, outFile)
                            }
                            saving = false
                            if (!r.ok) {
                                msg = r.message
                            } else {
                                msg = "已写入：" + (r.outputPath ?: "")
                                // 重新加载修改后的文件，刷新视图
                                val parsed = withContext(Dispatchers.IO) {
                                    try {
                                        ElfParser.parse(outFile.readBytes())
                                    } catch (ex: Exception) {
                                        null
                                    }
                                }
                                if (parsed != null) {
                                    editing = null
                                    onElfUpdated(parsed, outFile.name)
                                }
                            }
                        }
                    }
                ) {
                    Text(if (saving) "写入中..." else "写入",
                        color = if (editText.toByteArray(Charsets.UTF_8).size > target.maxLen)
                            InkFaint else Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            }
        )
    }
}

private fun fmtSize(b: Long): String =
    if (b > 1024 * 1024) String.format("%.2f MB", b / 1024.0 / 1024.0)
    else String.format("%.1f KB", b / 1024.0)

