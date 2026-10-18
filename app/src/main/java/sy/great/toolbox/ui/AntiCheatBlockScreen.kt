package sy.great.toolbox.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sy.great.toolbox.tools.IntegrityCheck
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.Good
import sy.great.toolbox.ui.theme.LocalPalette

/**
 * 阻断页。
 *
 * 两部分：
 *   1) 验证结果（只读，安全）—— 对应文档里 App 能真实执行的检测项
 *   2) 危险操作 —— 卸载模块，必须二次确认
 *
 * 文档中需要修改内核源码的场景（1.2/1.3/1.4/2.3/4）
 * 不由本页实现，也不伪造结果。
 */
@Composable
fun AntiCheatBlockScreen(onBack: () -> Unit = {}) {
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()

    var report by remember { mutableStateOf<IntegrityCheck.Report?>(null) }
    var loading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var confirmUnload by remember { mutableStateOf(false) }

    // 空壳化阻断状态
    var blocked by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var busyNote by remember { mutableStateOf("") }

    fun reload() {
        loading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { IntegrityCheck.verify() }
            val b = withContext(Dispatchers.IO) {
                try { IntegrityCheck.isBlocked() } catch (_: Throwable) { false }
            }
            report = r
            blocked = b
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        Modifier
            .fillMaxSize()
            .background(pal.paper)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        PageHeader("阻断", onBack)
        Spacer(Modifier.height(6.dp))
        Text(
            "内核完整性检测模块验证与阻断",
            fontSize = 11.sp, color = pal.inkSoft
        )
        Spacer(Modifier.height(14.dp))

        // ---- 刷新 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(
                text = if (loading) "检测中..." else "重新检测",
                enabled = !loading,
                onClick = { reload() }
            )
            Spacer(Modifier.width(10.dp))
            Text("读取模块与 /proc 节点状态", fontSize = 11.sp, color = pal.inkSoft)
        }
        Spacer(Modifier.height(14.dp))

        // ---- 无 root ----
        val r = report
        if (r != null && !r.hasRoot) {
            CardBlock {
                Text("需要 Root 权限", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = pal.ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "本页需要读取 /proc 下受保护的内核节点，\n必须拥有 Root 授权。",
                    fontSize = 12.sp, color = pal.inkSoft, lineHeight = 18.sp
                )
            }
            Spacer(Modifier.height(40.dp))
            return@Column
        }

        // ---- 验证结果 ----
        if (r != null) {
            Text("验证结果", fontSize = 12.sp, color = pal.inkSoft)
            Spacer(Modifier.height(8.dp))
            CardBlock {
                r.items.forEachIndexed { i, item ->
                    Row(verticalAlignment = Alignment.Top) {
                        // ✓ / ✗ / ?
                        val (mark, col) = when (item.ok) {
                            true -> "\u2713" to Good
                            false -> "\u2715" to Bad
                            null -> "?" to pal.inkFaint
                        }
                        Text(mark, fontSize = 14.sp, color = col, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, fontSize = 13.sp, color = pal.ink, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                item.detail,
                                fontSize = 11.sp, color = pal.inkSoft,
                                lineHeight = 16.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    if (i != r.items.lastIndex) {
                        Spacer(Modifier.height(10.dp))
                        HLine()
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            // 模块原始信息
            if (r.modulesLine != null) {
                Spacer(Modifier.height(10.dp))
                Text("模块原始记录", fontSize = 11.sp, color = pal.inkSoft)
                Spacer(Modifier.height(6.dp))
                CardBlock {
                    Text(
                        r.modulesLine,
                        fontSize = 11.sp, color = pal.ink,
                        fontFamily = FontFamily.Monospace, lineHeight = 16.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // ---- 空壳化阻断（推荐：可逆、不卸载模块）----
        Text("空壳化阻断", fontSize = 12.sp, color = pal.inkSoft)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(pal.paperSoft, RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "把 /proc/inte_* 绑定为 /dev/null",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .background(
                            if (blocked) Good.copy(alpha = 0.14f) else pal.line.copy(alpha = 0.5f),
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(
                        if (blocked) "已阻断" else "未阻断",
                        fontSize = 10.sp,
                        color = if (blocked) Good else pal.inkSoft
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "反作弊读这些节点时会拿到空内容，无法获取数据。\n" +
                        "驱动仍在运行，不卸载、不改内核，设备可正常使用。\n" +
                        "本操作全局生效，重启后自动失效。",
                fontSize = 11.sp, color = pal.inkSoft, lineHeight = 16.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    text = "执行阻断",
                    enabled = !blocked && !busy,
                    onClick = { confirmBlock = true }
                )
                PillButton(
                    text = "恢复",
                    filled = false,
                    enabled = blocked && !busy,
                    onClick = {
                        busy = true
                        busyNote = "正在恢复..."
                        scope.launch {
                            val list = withContext(Dispatchers.IO) { IntegrityCheck.unblockByBind() }
                            busy = false
                            busyNote = ""
                            msg = list.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.path + "：" + it.detail }
                            blocked = withContext(Dispatchers.IO) { IntegrityCheck.isBlocked() }
                            reload()
                        }
                    }
                )
            }
            if (busy && busyNote.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(busyNote, fontSize = 11.sp, color = Accent)
            }
        }

        Spacer(Modifier.height(18.dp))

        // ---- 危险操作 ----
        Text("危险操作", fontSize = 12.sp, color = Bad)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Bad.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Text(
                "\u26A0\uFE0F 以下操作会破坏系统安全模块，仅在授权测试环境使用",
                fontSize = 11.sp, color = Bad, lineHeight = 16.sp
            )
            Spacer(Modifier.height(12.dp))

            Text("卸载模块", fontSize = 13.sp, color = pal.ink, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                "执行 rmmod " + IntegrityCheck.MODULE_NAME + "\n" +
                        "卸载后检测能力立即终止，重启后恢复（如果模块会被开机重新加载）。",
                fontSize = 11.sp, color = pal.inkSoft, lineHeight = 16.sp
            )
            Spacer(Modifier.height(10.dp))
            PillButton(
                text = "卸载模块",
                filled = false,
                enabled = r?.loaded == true,
                onClick = { confirmUnload = true }
            )
            if (r?.loaded != true) {
                Spacer(Modifier.height(6.dp))
                Text("当前模块未加载，无需卸载", fontSize = 11.sp, color = pal.inkFaint)
            }
        }

        if (msg.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(msg, fontSize = 12.sp, color = Bad, lineHeight = 17.sp)
        }

        Spacer(Modifier.height(40.dp))
    }

    // ---- 二次确认 ----
    if (confirmUnload) {
        AlertDialog(
            onDismissRequest = { confirmUnload = false },
            title = { Text("确认卸载？") },
            text = {
                Text(
                    "即将执行：rmmod " + IntegrityCheck.MODULE_NAME + "\n\n" +
                            "这会卸载内核安全模块，检测能力将立即终止。\n" +
                            "确定要继续吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnload = false
                    scope.launch {
                        val (ok, info) = withContext(Dispatchers.IO) { IntegrityCheck.unloadModule() }
                        msg = if (ok) "卸载成功：" + info else "卸载失败：" + info
                        reload()
                    }
                }) {
                    Text("确认卸载", color = Bad)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnload = false }) { Text("取消") }
            }
        )
    }

    // ---- 阻断二次确认 ----
    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text("确认执行阻断？") },
            text = {
                Text(
                    "即将执行：\n" +
                            "mount --bind /dev/null /proc/inte_ko\n" +
                            "mount --bind /dev/null /proc/inte_status\n" +
                            "mount --bind /dev/null /proc/inte_systbl\n\n" +
                            "效果：读取这些节点会拿到空内容。\n" +
                            "可逆：可点「恢复」或重启后自动失效。\n" +
                            "确定继续吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlock = false
                    busy = true
                    busyNote = "正在执行..."
                    scope.launch {
                        val list = withContext(Dispatchers.IO) { IntegrityCheck.blockByBind() }
                        busy = false
                        busyNote = ""
                        msg = list.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.path + "：" + it.detail }
                        blocked = withContext(Dispatchers.IO) { IntegrityCheck.isBlocked() }
                        reload()
                    }
                }) {
                    Text("确认执行", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBlock = false }) { Text("取消") }
            }
        )
    }
}

// ---------------- 小组件 ----------------

@Composable
private fun CardBlock(content: @Composable () -> Unit) {
    val pal = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.paperSoft, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        content()
    }
}
