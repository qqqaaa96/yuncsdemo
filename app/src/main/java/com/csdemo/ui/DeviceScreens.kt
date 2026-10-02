package com.csdemo.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.tools.Device
import com.csdemo.tools.RootCheck
import com.csdemo.ui.theme.Bad
import com.csdemo.ui.theme.Good
import com.csdemo.ui.theme.Ink
import com.csdemo.ui.theme.InkFaint
import com.csdemo.ui.theme.InkSoft
import com.csdemo.ui.theme.Warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SectionTitle(text: String) {
    Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
}

@Composable
fun DeviceScreen() {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<List<Pair<String, String>>?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val d = withContext(Dispatchers.IO) {
            listOf(
                "硬件" to Device.cpuInfo(),
                "内存" to Device.memory(ctx),
                "存储" to Device.storage(),
                "屏幕" to Device.screen(ctx),
                "电池" to Device.battery(ctx),
                "网络" to Device.network(ctx),
                "系统" to Device.uptime(),
                "传感器" to Device.sensors(ctx)
            )
        }
        data = d
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("设备信息")
        Spacer(Modifier.height(12.dp))
        Card("基本") {
            RootCheck.deviceSummary().forEach { (k, v) -> KV(k, v) }
        }
        Spacer(Modifier.height(12.dp))

        val d = data
        if (d == null) {
            Card(null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 14.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在读取设备信息...", fontSize = 12.sp, color = InkSoft)
                }
                Spacer(Modifier.height(10.dp))
                ThinProgress(Modifier.fillMaxWidth().height(2.dp))
            }
        } else {
            d.forEachIndexed { idx, (title, body) ->
                Column(Modifier.staggerIn(idx)) {
                    Card(title) { Mono(body) }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun RootScreen() {
    var report by remember { mutableStateOf<RootCheck.Report?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runScan() {
        scanning = true
        report = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { RootCheck.scan() }
            report = r
            scanning = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { runScan() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Root 检测")
        Spacer(Modifier.height(6.dp))
        Text("多层交叉验证：su 存在性 → 授权 → 实际特权能力",
            fontSize = 11.sp, color = InkSoft)
        Spacer(Modifier.height(14.dp))

        // 扫描中
        if (scanning) {
            androidx.compose.animation.AnimatedVisibility(visible = true) {
                Card("检测中") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spinner(size = 16.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在拉起 su 并验证授权...", fontSize = 13.sp, color = InkSoft)
                    }
                    Spacer(Modifier.height(10.dp))
                    ThinProgress(Modifier.fillMaxWidth().height(2.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("如弹窗出现，请点“允许”", fontSize = 11.sp, color = InkSoft)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 结果
        val r = report
        if (r != null) {
            androidx.compose.animation.AnimatedVisibility(
                visible = true,
                enter = androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.tween(Motion.NORMAL)
                )
            ) {
                Column {
                    // 结论卡片
                    Card(null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (txt, col) = when (r.state) {
                                RootCheck.State.GRANTED -> "已获取 Root" to Good
                                RootCheck.State.DENIED -> "有 su，但未授权" to Warn
                                RootCheck.State.NO_SU -> "未检测到 Root" to InkSoft
                            }
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(col, androidx.compose.foundation.shape.CircleShape)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(txt, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = col)
                        }
                        Spacer(Modifier.height(10.dp))
                        val desc = when (r.state) {
                            RootCheck.State.GRANTED -> "已取得真实的 uid=0 权限，Root 功能可用。"
                            RootCheck.State.DENIED -> "系统存在 su，但未获得授权或授权被拒。请打开你的 Root 管理器授权本应用后重新检测。"
                            RootCheck.State.NO_SU -> "系统未发现可用的 su，这台设备未刷入 Root。"
                        }
                        Text(desc, fontSize = 12.sp, color = InkSoft, lineHeight = 18.sp)
                        Spacer(Modifier.height(10.dp))
                        HLine()
                        Spacer(Modifier.height(10.dp))
                        KV("su 路径", r.suPath, mono = true)
                        KV("Root 管理器", r.manager)
                        if (r.suVersion.isNotBlank()) KV("su 版本", r.suVersion, mono = true)
                        KV("本进程 uid", r.selfUid.ifBlank { "-" })
                        KV("耗时", r.usedMs.toString() + " ms")
                    }
                    Spacer(Modifier.height(12.dp))

                    Card("检测明细") {
                        r.items.forEachIndexed { i, it ->
                            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                                val color = when (it.level) {
                                    RootCheck.Level.OK -> Good
                                    RootCheck.Level.WARN -> Warn
                                    RootCheck.Level.FAIL -> Bad
                                    RootCheck.Level.INFO -> InkFaint
                                }
                                Text("●", fontSize = 12.sp, color = color)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(it.name, fontSize = 13.sp, color = Ink, fontWeight = FontWeight.Medium)
                                    if (it.detail.isNotBlank()) {
                                        Text(it.detail, fontSize = 11.sp, color = InkSoft, lineHeight = 16.sp)
                                    }
                                }
                            }
                            if (i != r.items.lastIndex) HLine()
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { runScan() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("重新检测") }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
