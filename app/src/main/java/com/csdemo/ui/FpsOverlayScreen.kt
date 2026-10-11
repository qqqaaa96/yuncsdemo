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
import kotlinx.coroutines.launch

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

    // 页面状态：每次「重新检测」或从系统设置回来都重读
    var canDraw by remember { mutableStateOf(FpsOverlayService.canDraw(ctx)) }
    var running by remember { mutableStateOf(FpsOverlayService.running) }
    var msg by remember { mutableStateOf("") }

    // Shizuku（ADB）状态
    var shizukuAvailable by remember { mutableStateOf(false) }
    var shizukuGranted by remember { mutableStateOf(false) }
    var shizukuNeeds by remember { mutableStateOf(false) }
    // root 状态
    var rootGranted by remember { mutableStateOf(false) }
    // 当前真实刷新率
    var refreshText by remember { mutableStateOf("检测中...") }
    // 是否正在检测
    var checking by remember { mutableStateOf(false) }

    /**
     * 重新检测：重读所有状态。
     *
     * 所有重活（Shizuku 调用 / dumpsys / su）都放到 IO 线程，
     * 结果回主线程，不阻塞界面。
     */
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun recheck() {
        checking = true
        scope.launch {
            // 重活放 IO
            val cd = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                FpsOverlayService.canDraw(ctx)
            }
            val rn = FpsOverlayService.running
            val rg = com.csdemo.tools.RootState.report.value?.granted == true
            val av = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.csdemo.tools.AdbShell.available()
            }
            val gr = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.csdemo.tools.AdbShell.granted()
            }
            val nd = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.csdemo.tools.AdbShell.needsRequest()
            }
            val txt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                FpsOverlayService.readRefreshRateForUi(ctx)
            }
            canDraw = cd
            running = rn
            rootGranted = rg
            shizukuAvailable = av
            shizukuGranted = gr
            shizukuNeeds = nd
            refreshText = txt
            checking = false
            msg = ""
        }
    }

    // 首次进入自动检测一次
    androidx.compose.runtime.LaunchedEffect(Unit) { recheck() }

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

        // ---- 当前刷新率 ----
        Card("当前刷新率") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    refreshText,
                    fontSize = 22.sp,
                    color = if (refreshText.contains("名义")) pal.inkSoft else Good,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "精确值来自 dumpsys SurfaceFlinger，需 Root 或 Shizuku（ADB）。\n" +
                        "两者都没有时只能读到名义值，会标注“名义”。",
                fontSize = 11.sp, color = pal.inkSoft, lineHeight = 16.sp
            )
        }
        Spacer(Modifier.height(12.dp))

        // ---- 提权状态 ----
        Card("提权状态") {
            Text(
                if (rootGranted) "Root：已授权" else "Root：未授权",
                fontSize = 13.sp,
                color = if (rootGranted) Good else pal.inkSoft,
            )
            Spacer(Modifier.height(6.dp))
            val szText = when {
                shizukuGranted -> "Shizuku：已授权（可读精确值）"
                shizukuNeeds -> "Shizuku：可用，但未授权"
                shizukuAvailable -> "Shizuku：可用，状态异常"
                else -> "Shizuku：未安装或未启动"
            }
            Text(
                szText,
                fontSize = 13.sp,
                color = if (shizukuGranted) Good else pal.inkSoft,
            )
            if (shizukuNeeds) {
                Spacer(Modifier.height(10.dp))
                PillButton(
                    text = "申请 Shizuku 授权",
                    onClick = {
                        try {
                            com.csdemo.tools.AdbShell.requestPermission()
                        } catch (_: Throwable) {
                        }
                        msg = "已发送授权请求，授权完成后点「重新检测」。"
                    }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 重新检测 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(
                text = if (checking) "检测中..." else "重新检测",
                enabled = !checking,
                onClick = { recheck() }
            )
            Spacer(Modifier.height(0.dp))
            Text(
                "   重新读取权限与刷新率",
                fontSize = 11.sp, color = pal.inkSoft,
            )
        }
        Spacer(Modifier.height(12.dp))

        // ---- 权限状态 ----
        Card("悬浮窗权限") {
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
