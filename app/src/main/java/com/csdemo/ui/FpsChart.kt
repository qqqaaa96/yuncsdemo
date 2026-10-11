package com.csdemo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.tools.FpsRecorder
import com.csdemo.ui.theme.Accent
import com.csdemo.ui.theme.LocalPalette
import java.util.Locale

/**
 * 录制结果：分成 5 张独立卡片。
 *
 * 每张卡片 = 标题 + 统计值 + 一条曲线。
 *
 *   1) FPS         —— 额外显示 5% Low 帧（卡顿参考）
 *   2) 温度
 *   3) CPU 频率
 *   4) CPU 使用率
 *   5) GPU 频率
 *
 * 曲线可横向滑动；某系列全是空值时该卡片显示“未采集到”（不画假线）。
 */

/** 每个采样点的像素宽度（越大越易看清，超出屏幕可左右滑） */
private const val PX_PER_SAMPLE = 2.4f

/** 单张图表高度 */
private val CHART_H = 120.dp

@Composable
fun FpsChartCard(samples: List<FpsRecorder.Sample>, title: String = "录制结果") {
    val pal = LocalPalette.current

    if (samples.isEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(pal.paperSoft, RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.inkSoft)
            Spacer(Modifier.height(8.dp))
            Text(
                "还没有录制数据。\n长按悬浮窗 2 秒开始录制（窗口会变橙色），录完回来这里看结果。",
                fontSize = 12.sp, color = pal.inkSoft, lineHeight = 17.sp
            )
        }
        return
    }

    val durMs = samples.lastOrNull()?.t ?: 0L

    Column(Modifier.fillMaxWidth()) {
        // ---- 顶部：录制概览 ----
        Text(
            title + "　" + samples.size + " 点　时长 " + fmtDuration(durMs),
            fontSize = 12.sp, color = pal.inkSoft,
        )
        Spacer(Modifier.height(10.dp))

        // ---- 5 张卡片 ----
        MetricCard(
            title = "FPS",
            color = Color(0xFF6E6E6E),
            unit = "FPS",
            values = samples.map { it.fps },
            fixedRange = 0f..60f,
            // FPS 额外统计：平均值 + 5% Low（卡顿感更强）
            extraStats = fpsExtraStats(samples.map { it.fps }),
        )
        Spacer(Modifier.height(10.dp))

        MetricCard(
            title = "温度",
            color = Color(0xFFE08A3C),
            unit = "°C",
            values = samples.map { it.tempC },
            autoRange = true,
        )
        Spacer(Modifier.height(10.dp))

        MetricCard(
            title = "CPU 频率",
            color = Color(0xFF2FA8A0),
            unit = "MHz",
            values = samples.map { it.cpuFreqMhz?.toFloat() },
            autoRange = true,
        )
        Spacer(Modifier.height(10.dp))

        MetricCard(
            title = "CPU 使用率",
            color = Color(0xFF4C8DFF),
            unit = "%",
            values = samples.map { it.cpuUsage },
            fixedRange = 0f..100f,
        )
        Spacer(Modifier.height(10.dp))

        MetricCard(
            title = "GPU 频率",
            color = Color(0xFF9B7BEA),
            unit = "MHz",
            values = samples.map { it.gpuMhz?.toFloat() },
            autoRange = true,
        )
    }
}

// ---------------- 单张指标卡片 ----------------

@Composable
private fun MetricCard(
    title: String,
    color: Color,
    unit: String,
    values: List<Float?>,
    fixedRange: ClosedFloatingPointRange<Float>? = null,
    autoRange: Boolean = false,
    extraStats: List<Pair<String, String>> = emptyList(),
) {
    val pal = LocalPalette.current
    val valid = values.filterNotNull()

    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.paperSoft, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        // ---- 标题 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(color, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)
        }

        // ---- 统计 ----
        Spacer(Modifier.height(8.dp))
        if (valid.isEmpty()) {
            Text("未采集到数据", fontSize = 11.sp, color = pal.inkFaint)
        } else {
            val avg = valid.average().toFloat()
            val max = valid.max()
            val min = valid.min()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatItem("平均", avg, unit)
                StatItem("最高", max, unit)
                StatItem("最低", min, unit)
            }
            // 额外统计（FPS 卡片的 5% Low）
            extraStats.forEach { (label, value) ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 11.sp, color = pal.inkFaint)
                    Spacer(Modifier.width(6.dp))
                    Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Accent)
                }
            }
        }

        // ---- 曲线 ----
        if (valid.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            val min = fixedRange?.start ?: (valid.min() * 0.95f)
            val max = fixedRange?.endInclusive ?: (valid.max() * 1.05f)
            // 防止平线时区间为 0
            val hi = if (max - min <= 0.01f) min + 1f else max
            val lo = if (max - min <= 0.01f) min - 1f else min

            val scroll = rememberScrollState()
            val totalW: Dp = (values.size * PX_PER_SAMPLE).dp
            Row(
                Modifier.fillMaxWidth().horizontalScroll(scroll)
            ) {
                Box(Modifier.width(totalW).height(CHART_H)) {
                    LineChart(
                        values = values,
                        lo = lo,
                        hi = hi,
                        color = color,
                        gridColor = pal.line,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: Float, unit: String) {
    val pal = LocalPalette.current
    Column {
        Text(label, fontSize = 10.sp, color = pal.inkFaint)
        Text(
            fmtNum(value) + (if (unit.isNotEmpty()) " " + unit else ""),
            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = pal.ink,
        )
    }
}

@Composable
private fun LineChart(
    values: List<Float?>,
    lo: Float,
    hi: Float,
    color: Color,
    gridColor: Color,
) {
    Canvas(Modifier.fillMaxWidth().height(CHART_H)) {
        val w = size.width
        val h = size.height
        if (values.size < 2 || w <= 0f) return@Canvas

        val span = (hi - lo).takeIf { it > 0f } ?: 1f

        // 网格：4 条水平虚线
        for (i in 0..4) {
            val y = h * i / 4f
            drawLine(
                gridColor, Offset(0f, y), Offset(w, y),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
        }

        // 折线：null 处断开（不连线、不补值）
        val stepX = w / (values.size - 1).toFloat()
        val path = Path()
        var started = false
        values.forEachIndexed { i, v ->
            if (v == null) {
                started = false
                return@forEachIndexed
            }
            val ratio = ((v - lo) / span).coerceIn(0f, 1f)
            val x = i * stepX
            val y = h - ratio * h
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        drawPath(path, color = color, style = Stroke(width = 2f, cap = StrokeCap.Round))
    }
}

// ---------------- 工具 ----------------

/**
 * FPS 的额外统计。
 *
 * 5% Low：把所有帧率从低到高排序，取第 5% 位置的值。
 * 它比平均值更能反映“卡不卡”——平均值会被大量高帧拉高。
 */
private fun fpsExtraStats(values: List<Float?>): List<Pair<String, String>> {
    val v = values.filterNotNull().sorted()
    if (v.isEmpty()) return emptyList()
    val idx = (v.size * 0.05f).toInt().coerceIn(0, v.size - 1)
    val low = v[idx]
    val smooth = v.count { it >= 45f } * 100f / v.size
    return listOf(
        "5% Low 帧" to fmtNum(low) + " FPS",
        "≥45FPS 占比" to String.format(Locale.US, "%.1f%%", smooth),
    )
}

private fun fmtNum(v: Float): String =
    if (v >= 100f) String.format(Locale.US, "%.0f", v)
    else String.format(Locale.US, "%.1f", v)

private fun fmtDuration(ms: Long): String {
    val totalSec = (ms / 1000L).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m > 0) m.toString() + "分" + s + "秒" else s.toString() + " 秒"
}
