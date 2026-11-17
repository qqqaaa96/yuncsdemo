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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.tools.mcp.McpServer
import sy.great.toolbox.tools.mcp.McpTools
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.Good
import sy.great.toolbox.ui.theme.LocalPalette
import sy.great.toolbox.ui.theme.Warn
import java.io.File
import java.security.SecureRandom

/**
 * MCP 分析页。
 *
 * 作用：把 App 变成一个 MCP 服务器，
 * 让外部 AI 客户端（Claude / Cursor / 任意支持 MCP 的工具）
 * 通过 HTTP 调用本机的 ELF 分析能力。
 *
 * 两个入口：
 *   本机   http://127.0.0.1:8989/mcp
 *   局域网 http://<手机IP>:8787/mcp
 *
 * 工作目录固定为 /storage/emulated/0/ELF分析处/mcp/
 * （上传 ELF 时放这里，工具只能访问这个目录内的文件）。
 */
/**
 * 完整页面（带返回栏）。独立进入 MCP 页时用。
 */
@Composable
fun McpAnalyzeScreen(onBack: () -> Unit) {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxSize().background(pal.paper)) {
        Spacer(Modifier.height(10.dp))
        Box(Modifier.padding(horizontal = 14.dp)) {
            PageHeader(title = "MCP 分析", onBack = onBack)
        }
        McpAnalyzeBody()
    }
}

/**
 * 内嵌内容（不带返回栏）。
 *
 * ELF 分析页的「MCP 分析」tab 用这个，
 * 因为那边已经有自己的顶栏，再画一个重复。
 */
@Composable
fun McpAnalyzeBody() {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val workDir = remember { File("/storage/emulated/0/ELF分析处/mcp") }

    // token 只在本次进程内有效
    val token = remember {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    val server = remember { McpServer(workDir, token).apply { tools = McpTools.all() } }

    var running by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(McpServer.State(token = token)) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var msg by remember { mutableStateOf("") }

    // 每秒刷新状态 + 文件列表
    LaunchedEffect(running) {
        while (running) {
            state = server.state
            files = McpToolFiles.list(workDir)
            kotlinx.coroutines.delay(1000)
        }
    }

    // 进入时扫描一次文件
    LaunchedEffect(Unit) {
        files = McpToolFiles.list(workDir)
        if (!workDir.exists()) {
            msg = "工作目录不存在：${workDir.absolutePath}\n请先在 ELF 分析页上传文件，或手动创建此目录。"
        }
    }

    DisposableEffect(Unit) {
        onDispose { server.stop() }
    }

    fun toggle() {
        if (running) {
            server.stop()
            running = false
            msg = "已停止"
        } else {
            val ok = server.start()
            running = ok
            state = server.state
            msg = if (ok) "已启动" else state.lastError
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(pal.paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
    ) {
        Spacer(Modifier.height(10.dp))

        // ---- 开关 ----
        Row(
            Modifier
                .fillMaxWidth()
                .background(pal.paperSoft, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (running) Good else pal.inkFaint)
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (running) "服务运行中" else "服务已停止",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (running) Good else pal.ink
                )
                Text(
                    if (running) "共 ${server.tools.size} 个工具可用" else "点右侧开关启动",
                    fontSize = 11.sp,
                    color = pal.inkSoft,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Box(
                Modifier
                    .background(if (running) Bad else Accent, RoundedCornerShape(18.dp))
                    .clickable { toggle() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    if (running) "停止" else "启动",
                    fontSize = 13.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (msg.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(msg, fontSize = 11.sp, color = msgColor(msg, pal), lineHeight = 16.sp)
        }

        Spacer(Modifier.height(12.dp))

        // ---- 连接地址 ----
        SectionCard("连接地址") {
            EndpointRow(
                label = "本机",
                url = "http://127.0.0.1:${McpServer.PORT_LOCAL}${McpServer.PATH}",
                pal = pal,
                onCopy = { clipboard.setText(AnnotatedString(it)); msg = "已复制" }
            )
            Spacer(Modifier.height(6.dp))
            EndpointRow(
                label = "局域网",
                url = if (state.lanAddress.isBlank())
                    "http://<手机IP>:${McpServer.PORT_LAN}${McpServer.PATH}"
                else
                    "http://${state.lanAddress}:${McpServer.PORT_LAN}${McpServer.PATH}",
                pal = pal,
                onCopy = { clipboard.setText(AnnotatedString(it)); msg = "已复制" }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "令牌（配到客户端 Authorization: Bearer <token>）",
                fontSize = 11.sp,
                color = pal.inkSoft
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    token,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = pal.ink,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "复制",
                    fontSize = 11.sp,
                    color = Accent,
                    modifier = Modifier
                        .clickable { clipboard.setText(AnnotatedString(token)); msg = "令牌已复制" }
                        .padding(8.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 工作目录 ----
        SectionCard("工作目录") {
            Text(
                workDir.absolutePath,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                color = pal.ink,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (files.isEmpty()) "目录内暂无 ELF 文件" else "共 ${files.size} 个 ELF 文件",
                fontSize = 11.sp,
                color = if (files.isEmpty()) Warn else Good
            )
            if (files.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                files.take(20).forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            f.name,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = pal.ink,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            formatSize(f.length()),
                            fontSize = 10.sp,
                            color = pal.inkFaint
                        )
                    }
                }
                if (files.size > 20) {
                    Text(
                        "…还有 ${files.size - 20} 个",
                        fontSize = 10.sp,
                        color = pal.inkFaint
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 工具清单 ----
        SectionCard("可用工具（${server.tools.size}）") {
            server.tools.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(13.dp)
                            .background(Accent, RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.name,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = Accent
                        )
                        Text(
                            t.description.lineSequence().firstOrNull().orEmpty(),
                            fontSize = 10.5.sp,
                            color = pal.inkSoft,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 统计 ----
        if (running) {
            SectionCard("运行统计") {
                StatRow("请求数", state.requests.toString(), pal)
                StatRow("工具调用", state.toolCalls.toString(), pal)
                StatRow("错误数", state.errors.toString(), pal)
                if (state.clients.isNotEmpty()) {
                    StatRow("客户端", state.clients.joinToString(", "), pal)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ---- 使用说明 ----
        SectionCard("怎么用") {
            Text(
                "1. 把 ELF 文件放到工作目录\n" +
                    "2. 点上面「启动」\n" +
                    "3. 在 AI 客户端（Claude Desktop / Cursor 等）里添加 MCP 服务器：\n" +
                    "   · Local 模式：adb forward tcp:8989 tcp:8989\n" +
                    "   · 或直接用局域网地址（手机和电脑同一 WiFi）\n" +
                    "4. 令牌填到 Authorization 头：Bearer <token>",
                fontSize = 11.sp,
                color = pal.inkSoft,
                lineHeight = 17.sp
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

/** 根据消息内容选颜色 */
private fun msgColor(msg: String, pal: sy.great.toolbox.ui.theme.Palette): Color = when {
    msg.contains("失败") || msg.contains("不存在") || msg.contains("错误") -> Bad
    msg.contains("复制") || msg.contains("启动") -> Good
    else -> pal.inkSoft
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    val pal = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.paperSoft, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.inkSoft)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun EndpointRow(
    label: String,
    url: String,
    pal: sy.great.toolbox.ui.theme.Palette,
    onCopy: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = pal.inkSoft, modifier = Modifier.width(46.dp))
        Text(
            url,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            color = pal.ink,
            modifier = Modifier.weight(1f)
        )
        Text(
            "复制",
            fontSize = 11.sp,
            color = Accent,
            modifier = Modifier.clickable { onCopy(url) }.padding(6.dp)
        )
    }
}

@Composable
private fun StatRow(label: String, value: String, pal: sy.great.toolbox.ui.theme.Palette) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 11.sp, color = pal.inkSoft)
        Text(value, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = pal.ink)
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** 文件列表辅助（复用 McpToolContext 的 ELF 判定） */
private object McpToolFiles {
    fun list(dir: File): List<File> {
        return try {
            dir.listFiles()
                ?.filter { it.isFile && isElf(it) }
                ?.sortedBy { it.name }
                ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun isElf(f: File): Boolean = try {
        f.inputStream().use { i ->
            val b = ByteArray(4)
            i.read(b) == 4 && b[0] == 0x7F.toByte() &&
                b[1] == 'E'.code.toByte() && b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()
        }
    } catch (_: Throwable) {
        false
    }
}
