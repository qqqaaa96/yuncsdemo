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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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
import sy.great.toolbox.tools.mcp.McpService
import sy.great.toolbox.tools.mcp.McpTools
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.Good
import sy.great.toolbox.ui.theme.LocalPalette
import sy.great.toolbox.ui.theme.Warn
import java.io.File

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

    val workDir = remember { McpService.workDir() }

    // 注意：server 由 McpService 持有，页面只读状态。
    //
    // 以前 server 在这里 remember 创建，离开页面就被 stop，
    // 客户端断连。现在改成前台 Service，切界面不影响。
    var activeToken by remember { mutableStateOf(McpService.token(ctx)) }
    var tokenEnabled by remember { mutableStateOf(McpService.tokenEnabled(ctx)) }
    var running by remember { mutableStateOf(McpService.running) }
    var state by remember { mutableStateOf(McpServer.State()) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var msg by remember { mutableStateOf("") }

    // 端口配置（改完需重启服务生效）
    var portText by remember { mutableStateOf(McpService.localPort(ctx).toString()) }
    var appliedPort by remember { mutableStateOf(McpService.localPort(ctx)) }

    // 局域网端口（0 = 不开启）
    var lanPortText by remember { mutableStateOf(McpService.lanPort(ctx).toString()) }
    var appliedLanPort by remember { mutableStateOf(McpService.lanPort(ctx)) }

    val scope = rememberCoroutineScope()

    // 工具数固定，不必反复取
    val toolList = remember { McpTools.all() }

    // 每秒刷新状态 + 文件列表（不管是否运行都刷，看清状态）
    LaunchedEffect(Unit) {
        while (true) {
            running = McpService.running
            McpService.current?.let { state = it.state }
            files = McpToolFiles.list(workDir)
            kotlinx.coroutines.delay(1000)
        }
    }

    fun toggle() {
        if (McpService.running) {
            McpService.stop(ctx)
            msg = "已停止"
        } else {
            // 启动是异步的：先给提示，再用轮询把结果回写，
            // 否则会永远停在「正在启动…」。
            McpService.start(ctx)
            msg = "正在启动…"
            scope.launch {
                // 最多等 3 秒（3 次）
                var waited = 0
                while (waited < 3 && !McpService.running) {
                    kotlinx.coroutines.delay(300)
                    waited++
                }
                if (McpService.running) {
                    msg = "服务已启动"
                } else {
                    val err = McpService.current?.state?.lastError ?: ""
                    msg = if (err.isNotBlank()) "启动失败：$err" else "启动失败：请检查端口是否被占用"
                }
            }
        }
    }

    fun regenerateToken() {
        activeToken = McpService.resetToken(ctx)
        msg = "令牌已重置，服务已重启，请更新客户端配置"
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
                    if (running) "共 ${toolList.size} 个工具可用 · 切界面不断连" else "点右侧开关启动",
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
            // URL 不再带 token：客户端直接填地址就能用。
            // 只有手动开启「token 认证」时才拼 ?token=。
            val suffix = if (tokenEnabled) "?token=$activeToken" else ""
            val localUrl = "http://127.0.0.1:$appliedPort${McpServer.PATH}$suffix"
            val lanPortNow = if (state.lanPort > 0) state.lanPort else McpServer.PORT_LAN
            val lanUrl = if (state.lanAddress.isBlank())
                "http://<手机IP>:$lanPortNow${McpServer.PATH}$suffix"
            else
                "http://${state.lanAddress}:$lanPortNow${McpServer.PATH}$suffix"

            EndpointRow(
                label = "本机",
                url = localUrl,
                pal = pal,
                onCopy = { clipboard.setText(AnnotatedString(it)); msg = "已复制" }
            )
            Spacer(Modifier.height(6.dp))
            EndpointRow(
                label = "局域网",
                url = lanUrl,
                pal = pal,
                onCopy = { clipboard.setText(AnnotatedString(it)); msg = "已复制" }
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "客户端直接填上面的地址即可，不需要额外配置。",
                fontSize = 10.5.sp,
                color = pal.inkSoft,
                lineHeight = 15.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- 端口设置 ----
        SectionCard("端口设置") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("本机端口", fontSize = 11.5.sp, color = pal.inkSoft, modifier = Modifier.width(66.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = portText,
                    onValueChange = { v ->
                        // 只允许数字，最长 5 位
                        val digits = v.filter { it.isDigit() }.take(5)
                        portText = digits
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("局域网端口", fontSize = 11.5.sp, color = pal.inkSoft, modifier = Modifier.width(66.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = lanPortText,
                    onValueChange = { v ->
                        val digits = v.filter { it.isDigit() }.take(5)
                        lanPortText = digits
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "本机默认 8903；局域网默认 8787。可填 1024 ~ 65535，局域网填 0 = 不开启。生效需重启服务。",
                fontSize = 10.sp,
                color = pal.inkFaint,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .background(Accent, RoundedCornerShape(16.dp))
                        .clickable {
                            val p = portText.toIntOrNull()
                            val lp = lanPortText.toIntOrNull()
                            if (p == null || !McpServer.isValidPort(p)) {
                                msg = "本机端口需在 1024 ~ 65535 之间"
                            } else if (lp == null || (lp != 0 && !McpServer.isValidPort(lp))) {
                                msg = "局域网端口需为 0 或 1024 ~ 65535"
                            } else {
                                val okLocal = McpService.setLocalPort(ctx, p)
                                val okLan = McpService.setLanPort(ctx, lp)
                                if (okLocal && okLan) {
                                    appliedPort = p
                                    appliedLanPort = lp
                                    if (McpService.running) {
                                        McpService.restart(ctx)
                                        msg = "端口已保存（本机 $p / 局域网 $lp），服务已重启"
                                    } else {
                                        msg = "端口已保存（本机 $p / 局域网 $lp）"
                                    }
                                } else {
                                    msg = "端口保存失败"
                                }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text("保存端口", fontSize = 12.sp, color = Color.White)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "当前：$appliedPort / $appliedLanPort",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = pal.inkSoft
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 令牌认证（可选，默认关闭） ----
        SectionCard("令牌认证") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (tokenEnabled) "已开启（URL 需带 token）" else "已关闭（直接填地址就能用）",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (tokenEnabled) Warn else pal.ink
                    )
                    Text(
                        if (tokenEnabled) "局域网建议开启，否则同 WiFi 任何人都能读写" else "本机使用无需令牌",
                        fontSize = 10.5.sp,
                        color = pal.inkSoft,
                        lineHeight = 15.sp
                    )
                }
                Box(
                    Modifier
                        .background(if (tokenEnabled) Warn else pal.inkFaint, RoundedCornerShape(16.dp))
                        .clickable {
                            tokenEnabled = !tokenEnabled
                            McpService.setTokenEnabled(ctx, tokenEnabled)
                            if (McpService.running) {
                                McpService.restart(ctx)
                            }
                            msg = if (tokenEnabled) "令牌认证已开启，服务已重启" else "令牌认证已关闭，服务已重启"
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        if (tokenEnabled) "关闭" else "开启",
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
            }
            if (tokenEnabled) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        activeToken,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = pal.ink,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "复制",
                        fontSize = 11.sp,
                        color = Accent,
                        modifier = Modifier
                            .clickable { clipboard.setText(AnnotatedString(activeToken)); msg = "令牌已复制" }
                            .padding(6.dp)
                    )
                    Text(
                        "重置",
                        fontSize = 11.sp,
                        color = Bad,
                        modifier = Modifier
                            .clickable { regenerateToken() }
                            .padding(6.dp)
                    )
                }
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
        SectionCard("可用工具（${toolList.size}）") {
            toolList.forEach { t ->
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
                "1. 把 ELF 文件放到工作目录（ELF 逆向选文件时会自动复制进来）\n" +
                    "2. 点上面「启动」\n" +
                    "3. 在 AI 客户端（Claude Desktop / Cursor 等）里添加 MCP 服务器，" +
                    "地址直接填上面的本机 URL，不要加任何参数：\n" +
                    "   · 同机：http://127.0.0.1:$appliedPort${McpServer.PATH}\n" +
                    "   · 手机与电脑同一 WiFi：用上面的局域网地址\n" +
                    "4. 若开启了「令牌认证」，URL 后面才会带 ?token=xxx",
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
