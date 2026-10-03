package com.csdemo.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.tools.Net
import com.csdemo.ui.theme.InkSoft
import com.csdemo.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun ToolInput(
    label: String,
    value: String,
    number: Boolean = false,
    onValue: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text
        )
    )
}

@Composable
fun PingScreen(onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("8.8.8.8") }
    var out by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    val pal = LocalPalette.current
    Column(Modifier.fillMaxSize().background(pal.paper).verticalScroll(rememberScrollState()).padding(16.dp)) {
        PageHeader("Ping", onBack)
        Spacer(Modifier.height(12.dp))
        ToolInput("主机 / 域名", host) { v -> host = v }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                out = ""; running = true
                scope.launch {
                    val sb = StringBuilder()
                    withContext(Dispatchers.IO) {
                        Net.ping(host.trim(), 4) { line ->
                            sb.append(line.text).append('\n')
                            val snap = sb.toString()
                            scope.launch(Dispatchers.Main) { out = snap }
                        }
                    }
                    running = false
                }
            },
            enabled = !running && host.isNotBlank()
        ) { Text(if (running) "执行中..." else "开始 Ping") }
        Spacer(Modifier.height(14.dp))
        Mono(out)
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun DnsScreen(onBack: () -> Unit = {}) {
    var host by remember { mutableStateOf("github.com") }
    var result by remember { mutableStateOf("") }

    val pal = LocalPalette.current
    Column(Modifier.fillMaxSize().background(pal.paper).verticalScroll(rememberScrollState()).padding(16.dp)) {
        PageHeader("DNS 查询", onBack)
        Spacer(Modifier.height(12.dp))
        ToolInput("域名", host) { v -> host = v }
        Spacer(Modifier.height(10.dp))
        Button(onClick = {
            val list = Net.resolve(host.trim())
            val sb = StringBuilder()
            var firstA: String? = null
            list.forEach { (t, v) ->
                sb.append(t).append("   ").append(v).append('\n')
                if (t == "A" && firstA == null) firstA = v
            }
            if (firstA != null) {
                sb.append("PTR   ").append(Net.reverse(firstA!!)).append('\n')
            }
            result = sb.toString()
        }, enabled = host.isNotBlank()) { Text("解析") }
        Spacer(Modifier.height(14.dp))
        Mono(result)
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun PortScanScreen(onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("192.168.1.1") }
    var from by remember { mutableStateOf("1") }
    var to by remember { mutableStateOf("1024") }
    var out by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(0) }

    val pal = LocalPalette.current
    Column(Modifier.fillMaxSize().background(pal.paper).verticalScroll(rememberScrollState()).padding(16.dp)) {
        PageHeader("端口扫描", onBack)
        Spacer(Modifier.height(6.dp))
        Text("仅扫描你自己的设备或有授权的网络", fontSize = 11.sp, color = pal.inkSoft)
        Spacer(Modifier.height(12.dp))
        ToolInput("目标 IP / 域名", host) { v -> host = v }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { ToolInput("起始端口", from, number = true) { v -> from = v } }
            Box(Modifier.weight(1f)) { ToolInput("结束端口", to, number = true) { v -> to = v } }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = {
            val a = from.toIntOrNull() ?: 1
            val b = to.toIntOrNull() ?: 1024
            if (b < a || b - a > 4096) {
                out = "端口范围无效（最大 4096 个）"
                return@Button
            }
            out = ""; running = true; done = 0
            scope.launch {
                val sb = StringBuilder()
                withContext(Dispatchers.IO) {
                    for (p in a..b) {
                        val open = Net.isOpen(host.trim(), p)
                        if (open) {
                            val banner = Net.banner(host.trim(), p).take(60).replace("\n", " ")
                            val svc = Net.serviceName(p)
                            val line = p.toString() + "/tcp  开放  " + svc +
                                    (if (banner.isNotBlank()) "   " + banner else "") + "\n"
                            sb.append(line)
                            val snap = sb.toString()
                            withContext(Dispatchers.Main) { out = snap }
                        }
                        withContext(Dispatchers.Main) { done = p - a + 1 }
                    }
                }
                withContext(Dispatchers.Main) {
                    out = sb.toString() + "\n完成，共探测 " + (b - a + 1) + " 个端口。"
                    running = false
                }
            }
        }, enabled = !running && host.isNotBlank()) {
            Text(if (running) ("扫描中 " + done) else "开始扫描")
        }
        Spacer(Modifier.height(14.dp))
        Mono(out)
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun HttpScreen(onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("https://www.baidu.com") }
    var info by remember { mutableStateOf<Net.HttpInfo?>(null) }

    val pal = LocalPalette.current
    Column(Modifier.fillMaxSize().background(pal.paper).verticalScroll(rememberScrollState()).padding(16.dp)) {
        PageHeader("HTTP 检测", onBack)
        Spacer(Modifier.height(12.dp))
        ToolInput("URL", url) { v -> url = v }
        Spacer(Modifier.height(10.dp))
        Button(onClick = {
            scope.launch {
                info = withContext(Dispatchers.IO) { Net.http(url.trim()) }
            }
        }, enabled = url.isNotBlank()) { Text("请求") }
        Spacer(Modifier.height(14.dp))
        val i = info
        if (i != null) {
            Card("响应") {
                if (i.error != null) {
                    KV("错误", i.error)
                } else {
                    KV("状态", i.statusLine)
                    KV("加密", if (i.secure) "HTTPS" else "HTTP")
                    KV("Server", i.server)
                    KV("Powered-By", i.poweredBy)
                    KV("Content-Type", i.contentType)
                    KV("长度", i.length)
                }
            }
            if (i.headers.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card("响应头（" + i.headers.size + "）") {
                    i.headers.forEach { (k, v) -> KV(k, v, mono = true) }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun LanScreen(onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var prefix by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val ip = Net.localIpv4()
        if (ip != null) prefix = ip.first.substringBeforeLast('.')
    }

    val pal = LocalPalette.current
    Column(Modifier.fillMaxSize().background(pal.paper).verticalScroll(rememberScrollState()).padding(16.dp)) {
        PageHeader("局域网扫描", onBack)
        Spacer(Modifier.height(12.dp))
        ToolInput("网段前缀", prefix) { v -> prefix = v }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                val tbl = Net.arpTable()
                out = if (tbl.isEmpty()) "ARP 表为空（部分设备需 root 才能读取）"
                else tbl.joinToString("\n") { it.first + "  " + (if (it.third.isBlank()) it.second else it.third) }
            }) { Text("读 ARP 表") }
            Button(onClick = {
                out = ""; running = true
                scope.launch {
                    val found = ArrayList<String>()
                    withContext(Dispatchers.IO) {
                        Net.sweep(prefix.trim()) { ip ->
                            if (!found.contains(ip)) {
                                found.add(ip)
                                val snap = found.joinToString("\n") { "存活  " + it } + "\n"
                                scope.launch(Dispatchers.Main) { out = snap }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        out = found.joinToString("\n") { "存活  " + it } +
                                "\n\n共发现 " + found.size + " 台设备。"
                        running = false
                    }
                }
            }, enabled = !running && prefix.isNotBlank()) {
                Text(if (running) "扫描中..." else "扫描网段")
            }
        }
        Spacer(Modifier.height(14.dp))
        Mono(out)
        Spacer(Modifier.height(40.dp))
    }
}
