package com.csdemo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.tools.Selinux
import com.csdemo.ui.theme.Bad
import com.csdemo.ui.theme.Good
import com.csdemo.ui.theme.Ink
import com.csdemo.ui.theme.InkFaint
import com.csdemo.ui.theme.InkSoft
import com.csdemo.ui.theme.LocalPalette
import com.csdemo.ui.theme.Warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SelinuxScreen(onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Selinux.State?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var confirmTarget by remember { mutableStateOf<Selinux.Mode?>(null) }
    val rootState = rememberRootState()

    fun load() {
        busy = true
        scope.launch {
            state = withContext(Dispatchers.IO) { Selinux.read() }
            busy = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        PageHeader("SELinux 管理", onBack)
        Spacer(Modifier.height(6.dp))
        Text("查看当前模式并切换强制 / 宽容。需 Root，重启后恢复默认。",
            fontSize = 11.sp, color = LocalPalette.current.inkSoft, lineHeight = 16.sp)
        Spacer(Modifier.height(12.dp))
        RootBanner(rootState)

        val s = state
        if (s == null) {
            Card(null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 14.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("读取中...", fontSize = 12.sp, color = LocalPalette.current.inkSoft)
                }
            }
        } else {
            // 当前模式
            Card("当前状态") {
                val (label, color) = when (s.mode) {
                    Selinux.Mode.ENFORCING -> "强制模式 Enforcing" to Good
                    Selinux.Mode.PERMISSIVE -> "宽容模式 Permissive" to Warn
                    Selinux.Mode.DISABLED -> "已禁用 Disabled" to Bad
                    Selinux.Mode.UNKNOWN -> "未知" to LocalPalette.current.inkFaint
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(color, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = color)
                }
                Spacer(Modifier.height(10.dp))
                val desc = when (s.mode) {
                    Selinux.Mode.ENFORCING ->
                        "系统正在强制执行安全策略。每个进程只能访问被允许的资源，安全性最高。"
                    Selinux.Mode.PERMISSIVE ->
                        "策略已加载但不拦截，违规操作只记日志。调试方便，但隔离形同虚设。"
                    Selinux.Mode.DISABLED ->
                        "内核编译时未启用 SELinux，无法通过 setenforce 改变，需重启到 bootloader 修改内核参数。"
                    Selinux.Mode.UNKNOWN -> "无法判断当前模式。"
                }
                Text(desc, fontSize = 12.sp, color = LocalPalette.current.inkSoft, lineHeight = 18.sp)
                Spacer(Modifier.height(10.dp))
                HLine()
                Spacer(Modifier.height(10.dp))
                KV("原始输出", s.raw)
                KV("selinuxfs", if (s.mounted) "已挂载" else "未挂载")
                KV("可写", if (s.canWrite) "是" else "否（只读或无权限）")
                if (s.policyVersion.isNotBlank()) KV("策略版本", s.policyVersion)
                if (s.currentContext.isNotBlank()) KV("当前上下文", s.currentContext, mono = true)
            }
            Spacer(Modifier.height(12.dp))

            // 切换
            Card("切换模式") {
                if (!s.switchable) {
                    Text(
                        when (s.mode) {
                            Selinux.Mode.DISABLED -> "设备已禁用 SELinux，无法切换。"
                            else -> "当前环境无法写入，切换不可用（需要 root 且 selinuxfs 可写）。"
                        },
                        fontSize = 12.sp, color = Warn, lineHeight = 17.sp
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { confirmTarget = Selinux.Mode.ENFORCING },
                            enabled = !busy && s.mode != Selinux.Mode.ENFORCING,
                            modifier = Modifier.weight(1f)
                        ) { Text("切到强制") }
                        Button(
                            onClick = { confirmTarget = Selinux.Mode.PERMISSIVE },
                            enabled = !busy && s.mode != Selinux.Mode.PERMISSIVE,
                            modifier = Modifier.weight(1f)
                        ) { Text("切到宽容") }
                    }
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { load() }) { Text("刷新", fontSize = 12.sp) }
                if (busy) {
                    Spacer(Modifier.height(6.dp))
                    ThinProgress(Modifier.fillMaxWidth().height(2.dp))
                }
            }

            AnimatedVisibility(visible = msg.isNotBlank(), enter = fadeIn(tween(Motion.NORMAL))) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Card("结果") {
                        Text(msg, fontSize = 13.sp, color = LocalPalette.current.ink, lineHeight = 18.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card("风险提示") {
            listOf(
                "• 宽容模式会让所有 App 的权限隔离失效，仅建议在调试自己的设备时临时使用。",
                "• 部分设备（尤其带强验证的 ROM）可能禁止 setenforce 写入。",
                "• 重启后通常恢复为 ROM 默认模式（多是 Enforcing）。",
                "• 本操作不会修改任何分区，不会导致设备无法开机。"
            ).forEach {
                Text(it, fontSize = 11.sp, color = LocalPalette.current.inkSoft,
                    lineHeight = 17.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        Spacer(Modifier.height(40.dp))
    }

    // 二次确认
    val target = confirmTarget
    if (target != null) {
        val toPermissive = target == Selinux.Mode.PERMISSIVE
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text(if (toPermissive) "切到宽容模式？" else "切回强制模式？") },
            text = {
                Text(
                    if (toPermissive)
                        "宽容模式下，系统不再拦截违反安全策略的操作。\n\n会影响所有应用，安全性显著下降。建议仅在调试时临时使用，用完切回强制。"
                    else
                        "强制模式下系统会按安全策略拦截违规访问，这是 Android 的推荐状态。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmTarget = null
                    busy = true
                    msg = ""
                    scope.launch {
                        val (ok, text) = withContext(Dispatchers.IO) {
                            if (toPermissive) Selinux.setPermissive() else Selinux.setEnforcing()
                        }
                        msg = text
                        state = withContext(Dispatchers.IO) { Selinux.read() }
                        busy = false
                    }
                }) {
                    Text(if (toPermissive) "确认切换" else "确认",
                        color = if (toPermissive) Warn else Good)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) { Text("取消") }
            }
        )
    }
}
