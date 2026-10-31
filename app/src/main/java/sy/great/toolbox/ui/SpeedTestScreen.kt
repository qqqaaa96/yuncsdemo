package sy.great.toolbox.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.tools.SpeedTest
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.Good
import sy.great.toolbox.ui.theme.LocalPalette
import sy.great.toolbox.ui.theme.Warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * 网络测速页面。
 *
 * UI 与「金属探测器」保持同一套：表盘卡 + 数值卡 + 胶囊按钮 + 说明卡。
 * 只是把「磁场偏离量」换成「实时速率 Mbps」。
 *
 * 表盘颜色随阶段变化：
 *   延迟中 = 蓝 / 下行 = 蓝 → 青 / 上行 = 黄 → 红（越高越快）。
 *   颜色不是“警告”，而是“进度语义”。
 */
@Composable
fun SpeedTestScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()

    var tick by remember { mutableStateOf(SpeedTest.Tick()) }
    var result by remember { mutableStateOf<SpeedTest.Result?>(null) }
    var running by remember { mutableStateOf(false) }

    // 当前网络类型（进入页面检测一次，并在开始测速前重新确认）
    var netKind by remember { mutableStateOf(SpeedTest.detectNetKind(ctx)) }

    // 移动流量警告弹窗
    val showCellularWarn = remember { mutableStateOf(false) }

    // 上次结果（进入页面就展示，不用重测）
    val last = remember { SpeedTest.loadLast(ctx) }
    var lastAt by remember { mutableStateOf(last?.optLong("at", 0L) ?: 0L) }
    var lastDown by remember { mutableStateOf(last?.optDouble("down", 0.0) ?: 0.0) }
    var lastUp by remember { mutableStateOf(last?.optDouble("up", 0.0) ?: 0.0) }
    var lastLat by remember { mutableStateOf(last?.optInt("lat", 0) ?: 0) }
    var lastJit by remember { mutableStateOf(last?.optInt("jit", 0) ?: 0) }

    DisposableEffect(Unit) {
        onDispose { SpeedTest.cancel() }
    }

    // 注意顺序：reallyStart() 必须写在 start() 之前。
    // Kotlin 的局部函数按声明顺序解析，向后引用会报
    // Unresolved reference（交接文档 1.7）。
    fun reallyStart() {
        running = true
        result = null
        tick = SpeedTest.Tick()

        // 主线程 Handler：onTick 在 IO 线程被高频调用（最快 20次/秒），
        // 若每次都 scope.launch 一个协程，会堆积大量待执行任务，
        // 表盘反而卡顿。用 removeCallback + 重投递，保证同一时刻只排一个更新。
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var pending: SpeedTest.Tick? = null
        val flush = object : Runnable {
            override fun run() {
                pending?.let { tick = it }
                pending = null
            }
        }

        scope.launch {
            val r = withContext(Dispatchers.IO) {
                SpeedTest.run(
                    onTick = { p ->
                        main.post {
                            pending = p
                            main.removeCallbacks(flush)
                            main.post(flush)
                        }
                    }
                )
            }
            running = false
            if (r.ok) {
                result = r
                SpeedTest.saveLast(ctx, r)
                lastAt = System.currentTimeMillis()
                lastDown = r.downMbps
                lastUp = r.upMbps
                lastLat = r.latencyMs
                lastJit = r.jitterMs
            } else {
                result = r
            }
        }
    }

    fun start() {
        if (running) return
        // 每次开始前都重新确认网络，因为用户可能刚刚切了 Wi-Fi。
        // 如果在消耗移动流量，先弹窗提醒，不直接开跑。
        netKind = SpeedTest.detectNetKind(ctx)
        if (SpeedTest.isMetered(ctx)) {
            showCellularWarn.value = true
            return
        }
        reallyStart()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(pal.paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        PageHeader(title = "网络测速", onBack = onBack)
        Spacer(Modifier.height(8.dp))

        // ---- 网络类型提示条 ----
        NetKindBar(kind = netKind, pal = pal)

        Spacer(Modifier.height(8.dp))

        // ---- 表盘 ----
        DialCard2(tick = tick, running = running, result = result, pal = pal)

        Spacer(Modifier.height(12.dp))

        // ---- 数值 ----
        NumbersCard2(tick = tick, result = result, pal = pal)

        Spacer(Modifier.height(12.dp))

        // ---- 上次结果 ----
        if (lastAt > 0L) {
            LastCard(
                at = lastAt,
                down = lastDown,
                up = lastUp,
                lat = lastLat,
                jit = lastJit,
                pal = pal,
            )
            Spacer(Modifier.height(12.dp))
        }

        // ---- 按钮 ----
        Row(Modifier.fillMaxWidth()) {
            PillButton2(
                text = when {
                    running -> "取消测速"
                    else -> "开始测速"
                },
                enabled = true,
                onClick = {
                    if (running) SpeedTest.cancel() else start()
                }
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---- 说明（必须写清测的是什么）----
        LimitCard2(pal)

        Spacer(Modifier.height(120.dp))
    }

    // ---- 移动流量警告弹窗 ----
    // 复用项目已有的公告弹窗外壳（ConfirmDialogMiuix）
    if (showCellularWarn.value) {
        sy.great.toolbox.ui.component.ConfirmDialogMiuix(
            title = "当前使用移动数据",
            content = "你正在使用**移动数据**网络测速。\n\n" +
                "测速需要真实下载 / 上传数据，一次约消耗 **10 MB ~ 1 GB** 流量，具体取决于你的网速。\n\n" +
                "建议先切换到 Wi-Fi 再测。",
            confirmText = "继续测速",
            dismissText = "切换为 Wi-Fi",
            onConfirm = {
                showCellularWarn.value = false
                reallyStart()
            },
            onDismiss = {
                showCellularWarn.value = false
                SpeedTest.openWifiSettings(ctx)
            },
            showDialog = showCellularWarn,
        )
    }
}

/**
 * 顶部网络类型条。
 *
 * 用颜色直接表达“花钱风险”：
 *   Wi-Fi / 以太网 = 绿色（不花钱）
 *   移动数据      = 橙色（花钱，需注意）
 *   无网络        = 红色
 */
@Composable
private fun NetKindBar(kind: SpeedTest.NetKind, pal: sy.great.toolbox.ui.theme.Palette) {
    val (fg, bg) = when (kind) {
        SpeedTest.NetKind.WIFI, SpeedTest.NetKind.ETHERNET -> Good to Good.copy(alpha = 0.10f)
        SpeedTest.NetKind.CELLULAR -> Warn to Warn.copy(alpha = 0.12f)
        SpeedTest.NetKind.NONE -> Bad to Bad.copy(alpha = 0.10f)
        else -> pal.inkSoft to pal.paperSoft
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(6.dp).height(6.dp).background(fg, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(8.dp))
        Text(
            text = "当前网络：" + kind.label,
            fontSize = 12.sp,
            color = fg,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.weight(1f))
        if (kind == SpeedTest.NetKind.CELLULAR) {
            Text("注意流量", fontSize = 11.sp, color = fg)
        }
    }
}

@Composable
private fun DialCard2(
    tick: SpeedTest.Tick,
    running: Boolean,
    result: SpeedTest.Result?,
    pal: sy.great.toolbox.ui.theme.Palette,
) {
    // 表盘跟随 progress（整体进度），数值区显示当前速率
    val target = when {
        running -> tick.progress
        result?.ok == true -> 1f
        else -> 0f
    }
    val progress by animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f),
        animationSpec = tween(300),
        label = "speedProgress",
    )

    val ringColor = when {
        !running && result?.ok != true -> pal.line
        tick.phase == SpeedTest.Phase.UPLOAD -> Warn
        tick.phase == SpeedTest.Phase.DONE -> Good
        else -> Accent
    }

    // 中心大字：运行中显示当前速率，完成后显示下行速率
    val mainValue: String = when {
        running -> "%.1f".format(tick.currentMbps)
        result?.ok == true -> "%.1f".format(result.downMbps)
        else -> "--"
    }
    val subLabel: String = when {
        running -> when (tick.phase) {
            SpeedTest.Phase.PING -> "正在测延迟"
            SpeedTest.Phase.DOWNLOAD -> "下载中 Mbps"
            SpeedTest.Phase.UPLOAD -> "上传中 Mbps"
            else -> "准备中"
        }
        result?.ok == true -> "下载 Mbps"
        result != null -> "测速失败"
        else -> "Mbps"
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = minOf(cx, cy) * 0.86f
            val stroke = radius * 0.10f

            // 底环
            drawArc(
                color = pal.line,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            // 进度环
            if (progress > 0.001f) {
                drawArc(
                    color = ringColor,
                    startAngle = 135f,
                    sweepAngle = 270f * progress,
                    useCenter = false,
                    topLeft = Offset(cx - radius, cy - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }

            // 刻度点
            for (i in 0..8) {
                val frac = i / 8f
                val angle = Math.toRadians((135f + 270f * frac).toDouble())
                val rr = radius - stroke * 1.6f
                val x = cx + (rr * cos(angle)).toFloat()
                val y = cy + (rr * sin(angle)).toFloat()
                drawCircle(
                    color = if (frac <= progress) ringColor else pal.inkFaint,
                    radius = stroke * 0.22f,
                    center = Offset(x, y)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = mainValue,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                color = if (running) ringColor else pal.ink,
                fontFamily = FontFamily.Monospace
            )
            Text(text = subLabel, fontSize = 11.sp, color = pal.inkSoft)

            if (running) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .background(ringColor, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "%.0f%%".format(tick.progress * 100f),
                        fontSize = 11.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun NumbersCard2(
    tick: SpeedTest.Tick,
    result: SpeedTest.Result?,
    pal: sy.great.toolbox.ui.theme.Palette,
) {
    val r = result
    val okDone = r?.ok == true

    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.paperSoft, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        NumberRow2(
            "下载",
            when {
                okDone -> "%.2f Mbps".format(r!!.downMbps)
                tick.phase == SpeedTest.Phase.DOWNLOAD -> "%.2f Mbps".format(tick.currentMbps)
                else -> "--"
            },
            pal
        )
        NumberRow2(
            "上传",
            when {
                okDone -> "%.2f Mbps".format(r!!.upMbps)
                tick.phase == SpeedTest.Phase.UPLOAD -> "%.2f Mbps".format(tick.currentMbps)
                else -> "--"
            },
            pal
        )
        NumberRow2(
            "延迟",
            when {
                okDone -> r!!.latencyMs.toString() + " ms"
                tick.latencyMs > 0 -> tick.latencyMs.toString() + " ms"
                else -> "--"
            },
            pal
        )
        NumberRow2(
            "抖动",
            when {
                okDone -> r!!.jitterMs.toString() + " ms"
                tick.jitterMs > 0 -> tick.jitterMs.toString() + " ms"
                else -> "--"
            },
            pal
        )
        NumberRow2(
            "已传输",
            when {
                okDone -> formatBytes(r!!.downBytes + r.upBytes)
                tick.bytes > 0 -> formatBytes(tick.bytes)
                else -> "--"
            },
            pal
        )
        if (r != null && !r.ok && r.error.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(r.error, fontSize = 11.sp, color = Bad, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun NumberRow2(label: String, value: String, pal: sy.great.toolbox.ui.theme.Palette) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = pal.inkSoft)
        Text(value, fontSize = 12.sp, color = pal.ink, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LastCard(
    at: Long,
    down: Double,
    up: Double,
    lat: Int,
    jit: Int,
    pal: sy.great.toolbox.ui.theme.Palette,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.paperSoft, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("上次结果", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)
            Text(formatWhen(at), fontSize = 11.sp, color = pal.inkFaint)
        }
        Spacer(Modifier.height(8.dp))
        NumberRow2("下载", "%.2f Mbps".format(down), pal)
        NumberRow2("上传", "%.2f Mbps".format(up), pal)
        NumberRow2("延迟", lat.toString() + " ms　抖动 " + jit + " ms", pal)
    }
}

@Composable
private fun LimitCard2(pal: sy.great.toolbox.ui.theme.Palette) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.paperSoft, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text("使用说明", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "测速会真实下载 / 上传数据，时长约 16 秒（下载 10s + 上传 6s）。\n" +
            "流量不封顶：网速越快耗得越多，100Mbps 宽带约 200MB，千兆可能超过 1GB。",
            fontSize = 11.sp, color = pal.inkSoft, lineHeight = 17.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "测出的是「本机 ↔ 账号服务器」的带宽。如果服务器上行比你家的宽带小，\n" +
            "结果会偏小 —— 这是链路瓶颈，不是手机慢。",
            fontSize = 11.sp, color = Warn, lineHeight = 17.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "延迟越低越好（< 50ms 优秀）；抖动越小网络越稳。",
            fontSize = 11.sp, color = pal.inkSoft, lineHeight = 17.sp
        )
    }
}

@Composable
private fun PillButton2(text: String, enabled: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val bg = if (enabled) Accent else pal.line
    val fg = if (enabled) Color.White else pal.inkSoft
    Row(
        Modifier
            .background(bg, RoundedCornerShape(20.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(text, fontSize = 13.sp, color = fg, fontWeight = FontWeight.Medium)
    }
}

// ---------------- 工具函数 ----------------

/**
 * 字节数格式化。
 *
 * 超过 1024MB（即 1GB）后统一进位到 GB，不再往下堆 MB，
 * 避免出现 “1432.6 MB” 这种难读的数字。
 * 上不封顶：10GB、100GB 都能正常显示。
 */
private fun formatBytes(b: Long): String {
    val kb = 1024.0
    val gb = b / kb / kb / kb
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        b >= 1024L * 1024 -> "%.1f MB".format(b / kb / kb)
        b >= 1024L -> "%.0f KB".format(b / kb)
        else -> b.toString() + " B"
    }
}

private fun formatWhen(at: Long): String {
    val d = java.util.Date(at)
    return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(d)
}
