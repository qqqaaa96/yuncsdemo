package sy.great.toolbox.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import sy.great.toolbox.tools.MetalDetector
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.Good
import sy.great.toolbox.ui.theme.LocalPalette
import sy.great.toolbox.ui.theme.Warn
import kotlin.math.cos
import kotlin.math.sin

/**
 * 金属探测器页面。
 *
 * 磁力计方案：显示磁场总量、与基准的偏离、强度表盘。
 * 偏离超阈值时震动 + 提示音（逻辑在 MetalDetector 里）。
 */
@Composable
fun MetalDetectorScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current

    val detector = remember { MetalDetector(ctx) }
    val hasSensor = remember { detector.hasSensor() }
    var reading by remember { mutableStateOf<MetalDetector.Reading?>(null) }

    DisposableEffect(Unit) {
        detector.onReading = { reading = it }
        if (hasSensor) detector.start()
        onDispose {
            detector.onReading = null
            detector.stop()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(pal.paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        PageHeader(title = "金属探测器", onBack = onBack)
        Spacer(Modifier.height(8.dp))

        if (!hasSensor) {
            NoSensorCard(pal)
            return@Column
        }

        // ---- 表盘 ----
        val r = reading
        DialCard(r, pal)

        Spacer(Modifier.height(12.dp))

        // ---- 数值 ----
        NumbersCard(r, pal)

        Spacer(Modifier.height(12.dp))

        // ---- 校准按钮 ----
        Row(Modifier.fillMaxWidth()) {
            PillButton(
                text = if (r?.calibrated == true) "重新校零" else "校准中...",
                enabled = r?.calibrated == true,
                onClick = { detector.resetCalibration() }
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---- 使用说明（必须写清物理限制）----
        LimitCard(pal)

        Spacer(Modifier.height(120.dp))
    }
}

@Composable
private fun NoSensorCard(pal: sy.great.toolbox.ui.theme.Palette) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.paperSoft, RoundedCornerShape(14.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("该设备没有磁力计", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Bad)
        Spacer(Modifier.height(8.dp))
        Text(
            "金属探测依赖磁力传感器。\n设备未提供 Sensor.TYPE_MAGNETIC_FIELD，无法使用该功能。",
            fontSize = 12.sp, color = pal.inkSoft
        )
    }
}

@Composable
private fun DialCard(r: MetalDetector.Reading?, pal: sy.great.toolbox.ui.theme.Palette) {
    val intensity = r?.intensity ?: 0f
    val alerting = r?.alerting == true
    val ringColor = when {
        !alerting -> Accent
        intensity > 0.7f -> Bad
        else -> Warn
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

            // 强度环
            if (intensity > 0.001f) {
                drawArc(
                    color = ringColor,
                    startAngle = 135f,
                    sweepAngle = 270f * intensity.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(cx - radius, cy - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }

            // 刻度点（9 个）
            for (i in 0..8) {
                val frac = i / 8f
                val angle = Math.toRadians((135f + 270f * frac).toDouble())
                val rr = radius - stroke * 1.6f
                val x = cx + (rr * cos(angle)).toFloat()
                val y = cy + (rr * sin(angle)).toFloat()
                drawCircle(
                    color = if (frac <= intensity) ringColor else pal.inkFaint,
                    radius = stroke * 0.22f,
                    center = Offset(x, y)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when {
                    r == null -> "--"
                    !r.calibrated -> "校零中"
                    else -> String.format("%.1f", r.delta)
                },
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                color = if (alerting) ringColor else pal.ink,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = if (r?.calibrated == false) "请远离金属" else "偏离量 μT",
                fontSize = 11.sp, color = pal.inkSoft
            )
            if (alerting) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .background(ringColor, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text("检测到金属", fontSize = 11.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun NumbersCard(r: MetalDetector.Reading?, pal: sy.great.toolbox.ui.theme.Palette) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.paperSoft, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        NumberRow("当前磁场", if (r == null) "--" else String.format("%.2f μT", r.magnitude), pal)
        NumberRow("X 轴", if (r == null) "--" else String.format("%.2f", r.x), pal)
        NumberRow("Y 轴", if (r == null) "--" else String.format("%.2f", r.y), pal)
        NumberRow("Z 轴", if (r == null) "--" else String.format("%.2f", r.z), pal)
    }
}

@Composable
private fun NumberRow(label: String, value: String, pal: sy.great.toolbox.ui.theme.Palette) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = pal.inkSoft)
        Text(value, fontSize = 12.sp, color = pal.ink, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LimitCard(pal: sy.great.toolbox.ui.theme.Palette) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.paperSoft, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text("使用说明", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "只能探测铁磁性金属：铁、钢、镍、钴。\n" +
            "金、银、铜、铝、不锈钢测不出——这是物理原理限制，不是灵敏度问题。",
            fontSize = 11.sp, color = pal.inkSoft, lineHeight = 17.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "使用前请取下磁吸手机壳、磁吸支架，否则读数会持续偏高。",
            fontSize = 11.sp, color = Bad, lineHeight = 17.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "进入页面后会自动校零（约 1.5 秒），期间请远离金属物体。",
            fontSize = 11.sp, color = pal.inkSoft, lineHeight = 17.sp
        )
    }
}

@Composable
private fun PillButton(text: String, enabled: Boolean, onClick: () -> Unit) {
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
