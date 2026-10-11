package com.csdemo.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.tools.FpsOverlayService
import com.csdemo.ui.theme.Bad
import com.csdemo.ui.theme.Good
import com.csdemo.ui.theme.LocalPalette

/**
 * 刷新率悬浮窗设置页。
 *
 * 流程：
 *   1. 未授权「显示在其他应用上层」→ 显示提示，点击跳系统设置
 *   2. 已授权 → 显示开关，控制悬浮窗的启动 / 停止
 *
 * 悬浮窗本体在 FpsOverlayService（系统级，切到其它应用也可见）。
 */
@Composable
fun FpsOverlayScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current

    // 权限与运行状态需要每次回到页面时重读（用户可能刚从系统设置回来）
    var canDraw by remember { mutableStateOf(FpsOverlayService.canDraw(ctx)) }
    var running by remember { mutableStateOf(FpsOverlayService.running) }
    var msg by remember { mutableStateOf("") }

    // 从系统设置回来时刷新状态
    androidx.compose.runtime.LaunchedEffect(Unit) {
        canDraw = FpsOverlayService.canDraw(ctx)
        running = FpsOverlayService.running
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(pal.paper)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        PageHeader("刷新率悬浮窗", onBack)
        Spacer(Modifier.height(6.dp))
        Text(
            "在其它应用之上显示实时刷新率，支持拖动与点击切换大小。",
            fontSize = 11.sp, color = pal.inkSoft, lineHeight = 16.sp
        )
        Spacer(Modifier.height(14.dp))

        // ---- 权限状态 ----
        Card("权限") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (canDraw) "已获得「显示在其他应用上层」权限" else "尚未获得悬浮窗权限",
                    fontSize = 13.sp,
                    color = if (canDraw) Good else Bad,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (!canDraw) {
                Text(
                    "此权限不能弹窗申请，需手动到系统设置里开启。",
                    fontSize = 11.sp, color = pal.inkSoft, lineHeight = 16.sp
                )
                Spacer(Modifier.height(10.dp))
                PillButton(
                    text = "去开启权限",
                    onClick = {
                        try {
                            val i = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + ctx.packageName)
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(i)
                        } catch (_: Throwable) {
                            msg = "无法打开系统设置页，请手动到「设置 → 应用 → 本应用 → 显示在其他应用上层」开启。"
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 开关 ----
        Card("悬浮窗") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (running) "运行中" else "已关闭",
                    fontSize = 13.sp,
                    color = if (running) Good else pal.inkSoft,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "小形态只显示刷新率；点一下展开为刷新率 + CPU + 温度 + 内存；再点收回。" +
                        "\n可按住拖动到任意位置。",
                fontSize = 11.sp, color = pal.inkSoft, lineHeight = 16.sp
            )
            Spacer(Modifier.height(12.dp))
            Row {
                PillButton(
                    text = if (running) "关闭悬浮窗" else "开启悬浮窗",
                    filled = !running,
                    enabled = canDraw,
                    onClick = {
                        if (!canDraw) {
                            msg = "请先授予悬浮窗权限。"
                            return@PillButton
                        }
                        if (running) {
                            FpsOverlayService.stop(ctx)
                            running = false
                        } else {
                            FpsOverlayService.start(ctx)
                            running = true
                        }
                        msg = ""
                    }
                )
            }
            if (!canDraw) {
                Spacer(Modifier.height(8.dp))
                Text("需先在上一张卡片里开启权限。", fontSize = 11.sp, color = Bad)
            }
        }

        if (msg.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(msg, fontSize = 12.sp, color = Bad, lineHeight = 17.sp)
        }

        Spacer(Modifier.height(40.dp))
    }
}
