package com.csdemo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.tools.FpsRecorder
import com.csdemo.ui.theme.Accent
import com.csdemo.ui.theme.LocalPalette
import kotlin.math.roundToInt

/**
 * 帧率波形图（参考 Scene 的录制结果图，配色与布局换成本项目的）。
 *
 * 特点：
 *   · 多系列叠加：FPS / 温度 / CPU 频率 / CPU 使用率 / GPU 频率
 *   · 每个系列用不同的归一化区间映射到同一张图；
 *   · 横向可滑动查看（固定每采样点像素宽，超出屏幕就滑）；
 *   · 曲线为 null 的采样点直接断开，不用旧值补位（不伪造连续性）。
 */

/** 一个可绘制的系列 */
private data class Series(
    val name: String,
    val color: Color,
    val values: List<Float?>,
    val min: Float,
    val max: Float,
    /** 是否画在右轴（否则左轴） */
    val rightAxis: Boolean = false,
    /** 单位后缀，用于图例 */
    val unit: String = "",
)

/** 每个采样点的像素宽度；越大越易看清，超出屏幕就能左右滑 */
private const val PX_PER_SAMPLE = 2.2f

/** 图表高度 */
private val CHART_H = 170.dp

/** 左轴所占宽度（留给刻度文字） */
private val AXIS_W = 34.dp

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FpsChartCard(
    samples: List<FpsRecorder.Sample>,
    title: String = "录制结果",
) {
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
                "还没有录制数据。\n长按悬浮窗 2 秒开始录制，录完回来这里看波形。",
                fontSize = 12.sp, color = pal.inkSoft, lineHeight = 17.sp
            )
        }
        return
    }

    // 各系列（仅保留“至少有一个非空值”的）
    val fps = samples.map { it.fps }
    val temp = samples.map { it.tempC }
    val cpuMhz = samples.map { it.cpuFreqMhz?.toFloat() }
    val cpuPct = samples.map { it.cpuUsage }
    val gpuMhz = samples.map { it.gpuMhz?.toFloat() }

    val all: List<Series> = buildList {
        add(Series("FPS", Color(0xFF6E6E6E), fps, 0f, 60f, unit = ""))
        add(Series("温度", Color(0xFFE08A3C), temp, 20f, 100f, rightAxis = false, unit = "°C"))
        add(Series("CPU 频率", Color(0xFF2FA8A0), cpuMhz, 0f, 3000f, rightAxis = true, unit = "MHz"))
        add(Series("CPU 使用率", Color(0xFF4C8DFF), cpuPct, 0f, 100f, unit = "%"))
        add(Series("GPU 频率", Color(0xFF9B7BEA), gpuMhz, 0f, 1200f, rightAxis = true, unit = "MHz"))
    }.filter { s -> s.values.any { it != null } }

    val scroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.paperSoft, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        // ---- 标题 + 概要 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.inkSoft)
            Spacer(Modifier.width(8.dp))
            Text(
                samples.size.toString() + " 点",
                fontSize = 11.sp, color = pal.inkFaint
            )
        }

        // 时长
        val durMs = samples.lastOrNull()?.t ?: 0L
        Spacer(Modifier.height(4.dp))
        Text(
            "时长 " + fmtDuration(durMs),
            fontSize = 11.sp, color = pal.inkFaint
        )

        // ---- FPS 统计（Scene 同款四项） ----
        Spacer(Modifier.height(10.dp))
        val fpsVals = fps.filterNotNull()
        if (fpsVals.isNotEmpty()) {
            val avg = fpsVals.average().toFloat()
            val max = fpsVals.max()
            val min = fpsVals.min()
            val smooth = fpsVals.count { it >= 45f } * 100f / fpsVals.size
            Row(Modifier.fillMaxWidth()) {
                StatCell("MAX", String.format("%.1f", max), "FPS", Modifier.weight(1f))
                StatCell("MIN", String.format("%.1f", min), "FPS", Modifier.weight(1f))
                StatCell("AVG", String.format("%.1f", avg), "FPS", Modifier.weight(1f))
                StatCell("≥45FPS", String.format("%.1f%%", smooth), "平滑度", Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 波形（可左右滑） ----
        Row(Modifier.fillMaxWidth()) {
            // 左轴刻度（固定不滑，只画左轴系列的刻度）
            val leftAxis = all.firstOrNull { !it.rightAxis }
            AxisLabels(
                min = leftAxis?.min ?: 0f,
                max = leftAxis?.max ?: 100f,
                height = CHART_H,
            )

            Box(Modifier.weight(1f)) {
                val totalW = (samples.size * PX_PER_SAMPLE).dp
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scroll)
                ) {
                    Box(Modifier.width(totalW).height(CHART_H)) {
                        ChartCanvas(
                            series = all,
                            count = samples.size,
                            gridColor = pal.line,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- 图例 ----
        LegendRow(all, pal.inkSoft)
    }
}

// ---------------- 子组件 ----------------

@Composable
private fun StatCell(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = pal.inkFaint)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Accent)
        Text(unit, fontSize = 10.sp, color = pal.inkFaint)
    }
}

@Composable
private fun AxisLabels(min: Float, max: Float, height: androidx.compose.ui.unit.Dp) {
    val pal = LocalPalette.current
    Column(
        Modifier.width(AXIS_W).height(height).padding(end = 4.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
    ) {
        // 从上到下 5 个刻度
        for (i in 0..4) {
            val v = max - (max - min) * i / 4f
            Text(
                if (v >= 100f) v.roundToInt().toString() else String.format("%.0f", v),
                fontSize = 9.sp,
                color = pal.inkFaint,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ChartCanvas(
    series: List<Series>,
    count: Int,
    gridColor: Color,
) {
    Canvas(Modifier.fillMaxWidth().height(CHART_H)) {
        val w = size.width
        val h = size.height
        if (count < 2 || w <= 0f) return@Canvas

        // ---- 网格 ----
        val grid = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        for (i in 0..4) {
            val y = h * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f, pathEffect = grid.pathEffect)
        }

        // ---- 曲线 ----
        val stepX = w / (count - 1).toFloat()
        for (s in series) {
            val span = (s.max - s.min).takeIf { it > 0f } ?: 1f
            var started = false
            val path = Path()
            for (i in 0 until count) {
                val v = s.values.getOrNull(i) ?: continue
                val ratio = ((v - s.min) / span).coerceIn(0f, 1f)
                val x = i * stepX
                val y = h - ratio * h
                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                }
            }
            if (started) {
                drawPath(path, color = s.color, style = Stroke(width = 2f, cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun LegendRow(series: List<Series>, textColor: Color) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        series.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(s.color, CircleShape)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    s.name + if (s.unit.isNotEmpty()) "(" + s.unit + ")" else "",
                    fontSize = 11.sp, color = textColor,
                )
            }
        }
    }
}

private fun fmtDuration(ms: Long): String {
    val totalSec = (ms / 1000L).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m > 0) m.toString() + "分" + s + "秒" else s.toString() + " 秒"
}
