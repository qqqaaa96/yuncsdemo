package sy.great.toolbox.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import sy.great.toolbox.tools.FpsOverlayService
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.Good
import sy.great.toolbox.ui.theme.LocalPalette
import sy.great.toolbox.ui.theme.Warn
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
fun FpsOverlayScreen(
    onBack: () -> Unit = {},
    /** 打开「帧率记录」历史页 */
    onOpenHistory: () -> Unit = {},
) {
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
            val rg = sy.great.toolbox.tools.RootState.report.value?.granted == true
            val av = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                sy.great.toolbox.tools.AdbShell.available()
            }
            val gr = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                sy.great.toolbox.tools.AdbShell.granted()
            }
            val nd = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                sy.great.toolbox.tools.AdbShell.needsRequest()
            }
            // 刷新率不在这里读：它由下面的 16ms 循环持续更新。
            canDraw = cd
            running = rn
            rootGranted = rg
            shizukuAvailable = av
            shizukuGranted = gr
            shizukuNeeds = nd
            checking = false
            msg = ""
        }
    }

    // 首次进入自动检测一次
    androidx.compose.runtime.LaunchedEffect(Unit) { recheck() }

    /**
     * 当前刷新率：16ms 持续刷新（约 60 次/秒，屏幕帧率上限）。
     *
     * 与悬浮窗同一数据源（驱动实测节点，走 Shizuku）；
     * 值没变就不写 state，避免无意义重组。
     * 页面离开时协程自动取消，不会后台烧电。
     */
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            val txt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val r = sy.great.toolbox.tools.DisplayRateReader.read(ctx)
                sy.great.toolbox.tools.DisplayRateReader.format(r.hz) +
                        if (r.real) "" else "（名义）"
            }
            if (txt != refreshText) refreshText = txt
            kotlinx.coroutines.delay(16L)
        }
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
                "实时值读自驱动层实测节点（与 Scene 同一套）：\n" +
                        "/sys/class/drm/.../measured_fps 等，需 Root 或 Shizuku。\n" +
                        "两者都不可用时回退为名义值，会标注“（名义）”。\n" +
                        "Root 与 Shizuku 跑同一条命令，所以准确率一致。",
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
                            sy.great.toolbox.tools.AdbShell.requestPermission()
                        } catch (_: Throwable) {
                        }
                        msg = "已发送授权请求，授权完成后点「重新检测」。"
                    }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 游戏 FPS 探测（开发/验证用）----
        Card("游戏 FPS 探测") {
            Text(
                "真实游戏帧率无法凭空读取：\n" +
                        "不同 ROM 的 SurfaceFlinger 输出各不相同，\n" +
                        "必须先在本机确认它能拿到什么，再写解析。\n" +
                        "下面按钮会把原始输出显示出来（需 Root 或 Shizuku）。",
                fontSize = 11.sp, color = pal.inkSoft, lineHeight = 16.sp
            )
            Spacer(Modifier.height(10.dp))
            PillButton(
                text = "探测刷新率数据源",
                filled = false,
                onClick = {
                    msg = "探测中..."
                    scope.launch {
                        val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            probeFpsSources(ctx)
                        }
                        msg = out
                    }
                }
            )
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
            // 探测输出可能很长：用等宽字体展示，颜色不用红色（那不是错误）。
            Text(
                msg,
                fontSize = 11.sp,
                color = pal.inkSoft,
                lineHeight = 15.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---- 入口：帧率记录（与设置页同款的横向可点击行）----
        top.yukonga.miuix.kmp.basic.Card(
            modifier = Modifier.fillMaxWidth(),
            content = {
                top.yukonga.miuix.kmp.preference.ArrowPreference(
                    title = "帧率记录",
                    summary = "查看历史录制结果",
                    onClick = onOpenHistory,
                )
            }
        )

        Spacer(Modifier.height(14.dp))

        // ---- 录制结果波形 ----
        // 用“采样数量”作为 key：数据增长时自动重新取快照，
        // 不需要额外的心跳 state（否则会引起无意义重组）。
        var sampleCount by remember { mutableStateOf(sy.great.toolbox.tools.FpsRecorder.samples.size) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            while (true) {
                val n = sy.great.toolbox.tools.FpsRecorder.samples.size
                if (n != sampleCount) sampleCount = n
                kotlinx.coroutines.delay(500L)
            }
        }
        val samplesSnapshot = androidx.compose.runtime.remember(sampleCount) {
            sy.great.toolbox.tools.FpsRecorder.samples.toList()
        }

        // 录制中提示
        if (sy.great.toolbox.tools.FpsRecorder.recording) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "● 录制中（" +
                            (sy.great.toolbox.tools.FpsRecorder.elapsedMs() / 1000L) + " 秒）" +
                            "  点击悬浮窗可停止",
                    fontSize = 12.sp, color = Warn,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        FpsChartCard(samples = samplesSnapshot, title = "刷新率 / 帧率录制")

        Spacer(Modifier.height(40.dp))
    }
}

/**
 * 探测：以 root / Shizuku 跑 SurfaceFlinger 相关命令，返回原始输出（截断）。
 *
 * 目的不是“已经拿到游戏 FPS”，而是先看清本机到底能拿到什么。
 * 不同 ROM 的输出差异很大，拿到真实样本后才能写解析。
 *
 * 如果两种提权都不可用，直接如实告知。
 */
/**
 * 探测本机真正可用的刷新率数据源。
 *
 * 数据源清单与读取思路参考 Scene（com.omarea.library.shell.FpsUtils）：
 *   1) /sys/class/drm/sde-crtc-0/measured_fps                    高通 DRM 实测帧率
 *   2) /sys/devices/platform/soc/ae00000.qcom,mdss_mdp/drm/card0/
 *      card0-sde-crtc-0/measured_fps                            高通旧版路径
 *   3) /sys/class/graphics/fb0/measured_fps                      通用 framebuffer
 *   4) /sys/kernel/fpsgo/fstb/fpsgo_status                       联发科 fpsgo（需按表头解析）
 *   5) service call SurfaceFlinger 1013                          binder 调用兜底
 *
 * 注意：这些都需要 root 或 shell 权限，普通权限读不到（会回退，不是“不支持”）。
 * 探测的目的不是“已经拿到实时刷新率”，而是先看清本机到底哪个源可用、
 * 输出是什么格式；拿到真实样本后才能写解析。
 */
private fun probeFpsSources(ctx: android.content.Context): String {
    val useRoot = sy.great.toolbox.tools.RootState.useRoot()
    val useAdb = sy.great.toolbox.tools.AdbShell.granted()

    if (!useRoot && !useAdb) {
        return "无法探测：既没有可用的 Root，也没有已授权的 Shizuku。\n\n" +
                "这两条路径读的是同一个内核节点，只是执行身份不同，\n" +
                "所以只要其中一条可用，结果与准确率一致。"
    }

    // 同一个命令，两种执行身份
    fun run(cmd: String): String {
        return try {
            if (useRoot) {
                sy.great.toolbox.tools.Shell.runSu("-c", cmd, timeoutMs = 6000).result?.out.orEmpty()
            } else {
                sy.great.toolbox.tools.AdbShell.exec(ctx.applicationContext, cmd)?.out.orEmpty()
            }
        } catch (_: Throwable) {
            ""
        }
    }

    val paths = listOf(
        "/sys/class/drm/sde-crtc-0/measured_fps",
        "/sys/devices/platform/soc/ae00000.qcom,mdss_mdp/drm/card0/card0-sde-crtc-0/measured_fps",
        "/sys/class/graphics/fb0/measured_fps",
        "/sys/kernel/fpsgo/fstb/fpsgo_status",
    )

    val sb = StringBuilder()
    sb.append("提权方式：").append(if (useRoot) "root（su -c）" else "Shizuku（ADB shell）").append('\n')
    sb.append("（两种身份读同一节点，结果一致）\n")

    for (p in paths) {
        sb.append("\n————————\n")
        sb.append("路径：").append(p).append('\n')
        // 先看是否存在
        val exists = run("ls -l " + p).trim()
        if (exists.isBlank()) {
            sb.append("结果：不存在或不可读\n")
            continue
        }
        sb.append("存在：").append(exists).append('\n')
        val body = run("cat " + p).trim()
        sb.append("内容：")
        sb.append(if (body.isBlank()) "（空）" else clipRaw(body)).append('\n')
    }

    // binder 兜底
    sb.append("\n————————\n")
    sb.append("命令：service call SurfaceFlinger 1013\n")
    val sf = run("service call SurfaceFlinger 1013").trim()
    sb.append("结果：").append(if (sf.isBlank()) "（无输出）" else clipRaw(sf)).append('\n')

    return sb.toString()
}

/** 单项输出过长时截断，避免卡住界面 */
private fun clipRaw(s: String): String =
    if (s.length > 600) s.substring(0, 600) + "…（已截断）" else s
