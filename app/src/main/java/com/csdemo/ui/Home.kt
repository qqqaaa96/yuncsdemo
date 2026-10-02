package com.csdemo.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.tools.Plan
import com.csdemo.tools.RootCheck
import com.csdemo.ui.theme.Accent
import com.csdemo.ui.theme.Good
import com.csdemo.ui.theme.Ink
import com.csdemo.ui.theme.InkSoft
import com.csdemo.ui.theme.Line
import com.csdemo.ui.theme.Paper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Feature(
    val id: String,
    val name: String,
    val desc: String,
    val group: String,
    val icon: String,
    val needRoot: Boolean = false
)

val FEATURES = listOf(
    Feature("device", "设备信息", "硬件 / 系统 / 电池", "检测", "◍"),
    Feature("root", "Root 检测", "su / Magisk / 提权", "检测", "#"),
    Feature("apps", "应用列表", "包名 / 签名 / 权限", "检测", "▤"),
    Feature("ping", "Ping", "连通性与延迟", "网络", "∿"),
    Feature("dns", "DNS 查询", "A / AAAA / 反向", "网络", "◎"),
    Feature("port", "端口扫描", "TCP 探测与 banner", "网络", "⌗"),
    Feature("http", "HTTP 检测", "状态码与响应头", "网络", "⇄"),
    Feature("lan", "局域网扫描", "ARP / 存活主机", "网络", "⌂"),
    Feature("codec", "编码 / 哈希", "Base64 / URL / MD5", "工具", "⟨⟩"),
    Feature("cpu", "CPU 频率", "min / max 调频", "Root", "⚡", needRoot = true),
    Feature("sched", "调度设置", "governor / IO", "Root", "◈", needRoot = true),
    Feature("thermal", "温控墙", "温区与触发点", "Root", "♨", needRoot = true),
    Feature("thread", "线程优化", "优先级 / 内存", "Root", "≣", needRoot = true),
    Feature("spoof", "设备伪装", "改机型 / 芯片", "Root", "◑", needRoot = true),
    Feature("selinux", "SELinux 管理", "强制 / 宽容切换", "Root", "⊘", needRoot = true),
    Feature("elf", "ELF 逆向", "反汇编 / CFG / 符号", "逆向", "⬡"),
)

@Composable
fun HomeScreen(onOpen: (String) -> Unit, onGoPlan: () -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var rootState by remember { mutableStateOf<RootCheck.Report?>(null) }
    var lockedName by remember { mutableStateOf<String?>(null) }

    // 异步检测 Root，不阻塞首屏渲染
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) { RootCheck.scan() }
        rootState = r
    }

    val filtered = if (query.isBlank()) FEATURES
    else FEATURES.filter { it.name.contains(query, true) || it.desc.contains(query, true) }
    val groups = filtered.groupBy { it.group }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("工具箱", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(2.dp))
        Text(
            Build.MANUFACTURER + " " + Build.MODEL + "  ·  Android " + Build.VERSION.RELEASE,
            fontSize = 12.sp, color = InkSoft
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (Plan.plan.value == Plan.ROOT) Tag("Root 方案", Good)
            else Tag("基础方案", InkSoft)
            Spacer(Modifier.width(8.dp))
            val rs = rootState
            if (rs == null) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Spinner(size = 12.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("检测 Root...", fontSize = 11.sp, color = InkSoft)
                }
            } else {
                when (rs.state) {
                    RootCheck.State.GRANTED -> Tag("Root 已授权", Good)
                    RootCheck.State.DENIED -> Tag("su 未授权", com.csdemo.ui.theme.Warn)
                    RootCheck.State.NO_SU -> Tag("无 Root", InkSoft)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onGoPlan) { Text("切换方案", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索功能", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(14.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            groups.forEach { (group, groupItems) ->
                item(span = { GridItemSpan(2) }) {
                    Text(group, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = InkSoft,
                        modifier = Modifier.padding(top = 6.dp))
                }
                itemsIndexed(groupItems, key = { _, f -> f.id }) { idx, f ->
                    val locked = f.needRoot && !Plan.canUseRoot()
                    FeatureCard(f, locked, idx) {
                        if (locked) lockedName = f.name else onOpen(f.id)
                    }
                }
            }
        }
    }

    val ln = lockedName
    if (ln != null) {
        AlertDialog(
            onDismissRequest = { lockedName = null },
            title = { Text("需要 Root 方案") },
            text = { Text("「" + ln + "」属于 Root 功能。你当前是基础方案，\n切换到 Root 方案后即可使用。") },
            confirmButton = {
                TextButton(onClick = {
                    lockedName = null
                    onGoPlan()
                }) { Text("去切换") }
            },
            dismissButton = {
                TextButton(onClick = { lockedName = null }) { Text("知道了") }
            }
        )
    }
}

@Composable
private fun FeatureCard(f: Feature, locked: Boolean, index: Int, onClick: () -> Unit) {
    val nameColor = if (locked) com.csdemo.ui.theme.InkFaint else Ink
    val descColor = if (locked) com.csdemo.ui.theme.InkFaint else InkSoft
    val iconColor = if (locked) com.csdemo.ui.theme.InkFaint else Accent
    Column(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .staggerIn(index)
            .pressable(pressedScale = 0.955f, onClick = onClick)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .background(if (locked) com.csdemo.ui.theme.PaperSoft else Paper, RoundedCornerShape(12.dp))
            .padding(13.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(f.icon, fontSize = 17.sp, color = iconColor)
            Spacer(Modifier.weight(1f))
            if (locked) {
                Text("锁定", fontSize = 10.sp, color = com.csdemo.ui.theme.InkFaint)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = nameColor)
        Text(f.desc, fontSize = 11.sp, color = descColor)
    }
}
