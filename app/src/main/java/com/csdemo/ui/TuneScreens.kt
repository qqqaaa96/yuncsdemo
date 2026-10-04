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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.tools.RootTune
import com.csdemo.ui.theme.Ink
import com.csdemo.ui.theme.InkSoft
import com.csdemo.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 小按钮 */
@Composable
private fun SmallBtn(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) { Text(text, fontSize = 12.sp) }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 12.sp, color = LocalPalette.current.inkSoft)
}

/**
 * 各 Root 页共用的 Root 状态。异步获取，不阻塞页面渲染。
 */
@Composable
fun rememberRootState(): com.csdemo.tools.RootCheck.State? {
    var state by remember { mutableStateOf<com.csdemo.tools.RootCheck.State?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) { com.csdemo.tools.RootCheck.scan() }
        state = r.state
    }
    return state
}

/**
 * Root 状态横幅。未授权时在每个 Root 页顶部提醒，避免用户以为是坏了。
 */
@Composable
fun RootBanner(state: com.csdemo.tools.RootCheck.State?) {
    if (state == null) {
        Card(null) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Spinner(size = 14.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在检测 Root 授权...", fontSize = 12.sp, color = LocalPalette.current.inkSoft)
            }
        }
        Spacer(Modifier.height(12.dp))
        return
    }
    when (state) {
        com.csdemo.tools.RootCheck.State.GRANTED -> {
            Card(null) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp)
                            .background(com.csdemo.ui.theme.Good, androidx.compose.foundation.shape.CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Root 已授权，可读可写", fontSize = 12.sp, color = com.csdemo.ui.theme.Good)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        com.csdemo.tools.RootCheck.State.DENIED -> {
            Card(null) {
                Text("su 存在但未授权", fontSize = 13.sp,
                    color = com.csdemo.ui.theme.Warn, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("请在 Root 管理器里给本应用授权，否则下方只能读取、无法写入。",
                    fontSize = 11.sp, color = LocalPalette.current.inkSoft, lineHeight = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
        com.csdemo.tools.RootCheck.State.NO_SU -> {
            Card(null) {
                Text("未检测到 Root", fontSize = 13.sp,
                    color = LocalPalette.current.inkSoft, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("这台设备没有可用的 su，以下功能只能读取、不能写入。",
                    fontSize = 11.sp, color = LocalPalette.current.inkSoft, lineHeight = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** CPU 频率调节 */
@Composable
fun CpuFreqScreen(onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    // 关键（性能）：RootTune.cores() 要读 /sys 多个节点，属同步 IO。
    // 放在 remember{} 里会阻塞组合阶段，导致进入页面掉帧。
    // 先给一个默认值，异步扫描后再替换。
    var cores by remember { mutableStateOf(listOf(0)) }
    var selected by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val c = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { RootTune.cores() }.getOrDefault(listOf(0))
        }
        if (c.isNotEmpty()) {
            cores = c
            selected = c.first()
        }
    }
    var info by remember { mutableStateOf<RootTune.FreqInfo?>(null) }
    var minTxt by remember { mutableStateOf("") }
    var maxTxt by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        loading = true
        scope.launch {
            val f = withContext(Dispatchers.IO) { RootTune.freqOf(selected) }
            info = f
            minTxt = f.currentMin.toString()
            maxTxt = f.currentMax.toString()
            loading = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(selected) { refresh() }
    val rootState = rememberRootState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        PageHeader("CPU 频率调节", onBack)
        Spacer(Modifier.height(6.dp))
        Text("单位 kHz。修改需 Root。", fontSize = 11.sp, color = LocalPalette.current.inkSoft)
        Spacer(Modifier.height(12.dp))
        RootBanner(rootState)

        Card("核心") {
            ScrollRow(spacing = 8.dp) {
                cores.take(16).forEach { c ->
                    SmallBtn("cpu$c", enabled = c != selected) { selected = c }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Card("当前状态") {
            val f = info
            if (f == null) {
                Mono(if (loading) "读取中..." else "无数据（可能需要 Root）")
            } else {
                KV("核心", "cpu" + f.core)
                KV("当前频率", f.current.toString() + " kHz")
                KV("最小", f.currentMin.toString() + " kHz")
                KV("最大", f.currentMax.toString() + " kHz")
                KV("调度器", f.governor)
                Spacer(Modifier.height(8.dp))
                Label("可用频率")
                Spacer(Modifier.height(4.dp))
                Mono(f.available.joinToString("\n"))
            }
        }
        Spacer(Modifier.height(12.dp))

        Card("设置") {
            OutlinedTextField(
                value = maxTxt,
                onValueChange = { maxTxt = it },
                label = { Text("最大频率 kHz", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = minTxt,
                onValueChange = { minTxt = it },
                label = { Text("最小频率 kHz", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            ScrollRow(spacing = 8.dp) {
                SmallBtn("应用") {
                    val mn = minTxt.toLongOrNull()
                    val mx = maxTxt.toLongOrNull()
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { RootTune.setFreq(selected, mn, mx) }
                        msg = if (ok) "已提交修改" else "失败：未获取 Root"
                        refresh()
                    }
                }
                SmallBtn("恢复最大") {
                    val f = info
                    if (f != null && f.available.isNotEmpty()) {
                        val top = f.available.last()
                        maxTxt = top.toString()
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { RootTune.setFreq(selected, null, top) }
                            msg = if (ok) "已恢复上限" else "失败：未获取 Root"
                            refresh()
                        }
                    }
                }
                SmallBtn("刷新") { refresh() }
            }
            if (msg.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(msg, fontSize = 12.sp, color = LocalPalette.current.ink)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** 调度 */
@Composable
fun SchedScreen(onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var cores by remember { mutableStateOf(listOf(0)) }
    var selected by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val c = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { RootTune.cores() }.getOrDefault(listOf(0))
        }
        if (c.isNotEmpty()) {
            cores = c
            selected = c.first()
        }
    }
    var govs by remember { mutableStateOf<List<String>>(emptyList()) }
    var cur by remember { mutableStateOf("") }
    var ioAvail by remember { mutableStateOf<List<String>>(emptyList()) }
    var ioCur by remember { mutableStateOf("") }
    var ioRaw by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                Triple(
                    RootTune.availableGovernors(selected),
                    RootTune.freqOf(selected).governor,
                    RootTune.ioGovernor()
                )
            }
            govs = data.first
            cur = data.second
            ioRaw = data.third
            ioAvail = RootTune.ioAvailable()
            ioCur = RootTune.ioCurrent()
        }
    }

    androidx.compose.runtime.LaunchedEffect(selected) { refresh() }
    val rootState = rememberRootState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        PageHeader("调度设置", onBack)
        Spacer(Modifier.height(6.dp))
        Text("CPU 调度器与磁盘 IO 调度器。需 Root。", fontSize = 11.sp, color = LocalPalette.current.inkSoft)
        Spacer(Modifier.height(12.dp))
        RootBanner(rootState)

        Card("CPU 核心") {
            ScrollRow(spacing = 8.dp) {
                cores.take(16).forEach { c ->
                    SmallBtn("cpu$c", enabled = c != selected) { selected = c }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Card("CPU 调度器") {
            KV("当前", cur.ifBlank { "未知" })
            Spacer(Modifier.height(8.dp))
            if (govs.isEmpty()) {
                Mono(ioRaw.ifBlank { "读取不到可用调度器（可能需要 Root）" })
            } else {
                Label("可用")
                Spacer(Modifier.height(6.dp))
                Column {
                    govs.forEach { g ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(g, fontSize = 13.sp, color = LocalPalette.current.ink, modifier = Modifier.weight(1f))
                            if (g == cur) {
                                Text("当前", fontSize = 11.sp, color = LocalPalette.current.inkSoft)
                            } else {
                                SmallBtn("切换") {
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { RootTune.setGovernor(selected, g) }
                                        msg = if (ok) "已切换到 " + g else "失败：未获取 Root"
                                        refresh()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Card("磁盘 IO 调度器") {
            KV("原始", ioRaw.ifBlank { "读取不到" }, mono = true)
            Spacer(Modifier.height(8.dp))
            if (ioAvail.isEmpty()) {
                Text("无可切换项", fontSize = 12.sp, color = LocalPalette.current.inkSoft)
            } else {
                Column {
                    ioAvail.forEach { g ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(g, fontSize = 13.sp, color = LocalPalette.current.ink, modifier = Modifier.weight(1f))
                            if (g == ioCur) {
                                Text("当前", fontSize = 11.sp, color = LocalPalette.current.inkSoft)
                            } else {
                                SmallBtn("切换") {
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { RootTune.setIoGovernor("sda", g) }
                                        msg = if (ok) "已切换到 " + g else "失败：未获取 Root"
                                        refresh()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (msg.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Card("结果") { Text(msg, fontSize = 13.sp, color = LocalPalette.current.ink) }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** 温控墙 */
@Composable
fun ThermalScreen(onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var zones by remember { mutableStateOf<List<RootTune.ThermalZone>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    var tripTxt by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            zones = withContext(Dispatchers.IO) { RootTune.thermalZones() }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }
    val rootState = rememberRootState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        PageHeader("温控墙", onBack)
        Spacer(Modifier.height(6.dp))
        Text("读取各温区当前温度与触发点。修改需 Root，且不同内核支持程度不同。",
            fontSize = 11.sp, color = LocalPalette.current.inkSoft, lineHeight = 16.sp)
        Spacer(Modifier.height(12.dp))
        RootBanner(rootState)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallBtn("刷新温度") { refresh() }
        }
        Spacer(Modifier.height(12.dp))

        Card("温区（" + zones.size + "）") {
            if (zones.isEmpty()) {
                Mono("读取不到温区（可能需要 Root）")
            } else {
                zones.forEachIndexed { i, z ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(z.type, fontSize = 13.sp, color = LocalPalette.current.ink, fontWeight = FontWeight.Medium)
                            Text("zone" + z.id, fontSize = 11.sp, color = LocalPalette.current.inkSoft)
                        }
                        Text(
                            String.format("%.1f °C", z.temp),
                            fontSize = 13.sp,
                            color = when {
                                z.temp >= 60.0 -> com.csdemo.ui.theme.Bad
                                z.temp >= 45.0 -> com.csdemo.ui.theme.Warn
                                else -> LocalPalette.current.ink
                            }
                        )
                    }
                    if (i != zones.lastIndex) HLine()
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Card("设置触发点") {
            Text("写入 trip_point_0_temp（单位毫摄氏度，如 60000 = 60°C）",
                fontSize = 12.sp, color = LocalPalette.current.inkSoft, lineHeight = 17.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = tripTxt,
                        onValueChange = { tripTxt = it },
                        label = { Text("zone0 触发点", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                SmallBtn("应用") {
                    val v = tripTxt.trim().toLongOrNull()
                    if (v == null) {
                        msg = "请输入数字"
                    } else {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { RootTune.setThermalTrip(0, v) }
                            msg = if (ok) "已提交 zone0 触发点" else "失败：未获取 Root 或不支持"
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            SmallBtn("读取 zone0 触发点") {
                scope.launch {
                    val v = withContext(Dispatchers.IO) { RootTune.thermalTrip(0) }
                    msg = if (v.isBlank()) "读取不到（可能需要 Root）" else "zone0 触发点 = " + v
                }
            }
        }
        if (msg.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Card("结果") { Text(msg, fontSize = 13.sp, color = LocalPalette.current.ink) }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** 线程优化 */
@Composable
fun ThreadOptScreen(onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var pkg by remember { mutableStateOf("com.csdemo") }
    var nice by remember { mutableStateOf("-10") }
    var msg by remember { mutableStateOf("") }
    var dirtyRatio by remember { mutableStateOf("") }
    var swappiness by remember { mutableStateOf("") }

    fun loadVm() {
        scope.launch {
            val d = withContext(Dispatchers.IO) {
                RootTune.vmValue("dirty_ratio") to RootTune.vmValue("swappiness")
            }
            dirtyRatio = d.first
            swappiness = d.second
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { loadVm() }
    val rootState = rememberRootState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        PageHeader("线程优化", onBack)
        Spacer(Modifier.height(6.dp))
        Text("调整进程优先级与内存参数。需 Root。renice 值越小优先级越高，如 -10。",
            fontSize = 11.sp, color = LocalPalette.current.inkSoft, lineHeight = 16.sp)
        Spacer(Modifier.height(12.dp))
        RootBanner(rootState)

        Card("进程优先级") {
            OutlinedTextField(
                value = pkg,
                onValueChange = { pkg = it },
                label = { Text("包名", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = nice,
                onValueChange = { nice = it },
                label = { Text("niceness", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            SmallBtn("应用") {
                val n = nice.trim().toIntOrNull()
                if (n == null) {
                    msg = "niceness 需为整数"
                } else {
                    scope.launch {
                        msg = withContext(Dispatchers.IO) { RootTune.optimizeProcess(pkg.trim(), n) }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Card("内存参数") {
            KV("dirty_ratio", dirtyRatio.ifBlank { "-" })
            KV("swappiness", swappiness.ifBlank { "-" })
            Spacer(Modifier.height(8.dp))
            ScrollRow(spacing = 8.dp) {
                SmallBtn("读当前") { loadVm() }
                SmallBtn("swappiness=10") {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { RootTune.setVmValue("swappiness", "10") }
                        msg = if (ok) "已设 swappiness=10" else "失败：未获取 Root"
                        loadVm()
                    }
                }
                SmallBtn("清缓存") {
                    scope.launch {
                        msg = withContext(Dispatchers.IO) { RootTune.backgroundTune() }
                    }
                }
            }
        }
        if (msg.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Card("结果") { Text(msg, fontSize = 13.sp, color = LocalPalette.current.ink, lineHeight = 18.sp) }
        }
        Spacer(Modifier.height(40.dp))
    }
}
