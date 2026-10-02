package com.csdemo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 功能图标。
 *
 * 全部用 Canvas 绘制，不使用 Emoji 或位图。
 * 统一 24dp 画布 / 2dp 线宽 / 圆角线帽，风格与项目一致（细线条、几何、克制）。
 *
 * 通过 [FeatureIcon] 按功能 id 取图标；未知 id 回退为圆点。
 */
@Composable
fun FeatureIcon(
    id: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.09f
        val st = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (id) {
            "device" -> drawDevice(s, color, st)
            "root" -> drawRoot(s, color, st)
            "apps" -> drawApps(s, color, st)
            "ping" -> drawPing(s, color, st)
            "dns" -> drawDns(s, color, st)
            "port" -> drawPort(s, color, st)
            "http" -> drawHttp(s, color, st)
            "lan" -> drawLan(s, color, st)
            "codec" -> drawCodec(s, color, st)
            "cpu" -> drawCpu(s, color, st)
            "sched" -> drawSched(s, color, st)
            "thermal" -> drawThermal(s, color, st)
            "thread" -> drawThread(s, color, st)
            "spoof" -> drawSpoof(s, color, st)
            "selinux" -> drawShield(s, color, st)
            "elf" -> drawElf(s, color, st)
            else -> drawDot(s, color)
        }
    }
}

private fun DrawScope.drawDot(s: Float, c: Color) {
    drawCircle(color = c, radius = s * 0.12f, center = Offset(s / 2, s / 2))
}

/** 手机 */
private fun DrawScope.drawDevice(s: Float, c: Color, st: Stroke) {
    drawRoundRect(
        color = c,
        topLeft = Offset(s * 0.30f, s * 0.10f),
        size = Size(s * 0.40f, s * 0.80f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.09f),
        style = st,
    )
    drawLine(c, Offset(s * 0.44f, s * 0.80f), Offset(s * 0.56f, s * 0.80f), strokeWidth = st.width, cap = StrokeCap.Round)
}

/** # 号（root） */
private fun DrawScope.drawRoot(s: Float, c: Color, st: Stroke) {
    drawLine(c, Offset(s * 0.34f, s * 0.16f), Offset(s * 0.26f, s * 0.84f), strokeWidth = st.width, cap = StrokeCap.Round)
    drawLine(c, Offset(s * 0.66f, s * 0.16f), Offset(s * 0.58f, s * 0.84f), strokeWidth = st.width, cap = StrokeCap.Round)
    drawLine(c, Offset(s * 0.16f, s * 0.40f), Offset(s * 0.84f, s * 0.40f), strokeWidth = st.width, cap = StrokeCap.Round)
    drawLine(c, Offset(s * 0.16f, s * 0.62f), Offset(s * 0.84f, s * 0.62f), strokeWidth = st.width, cap = StrokeCap.Round)
}

/** 列表 */
private fun DrawScope.drawApps(s: Float, c: Color, st: Stroke) {
    for (i in 0 until 3) {
        val y = s * (0.24f + i * 0.26f)
        drawCircle(color = c, radius = s * 0.05f, center = Offset(s * 0.22f, y))
        drawLine(c, Offset(s * 0.40f, y), Offset(s * 0.80f, y), strokeWidth = st.width, cap = StrokeCap.Round)
    }
}

/** 信号波 */
private fun DrawScope.drawPing(s: Float, c: Color, st: Stroke) {
    val cx = s * 0.20f
    val cy = s * 0.72f
    for (i in 1..3) {
        val r = s * 0.18f * i
        drawArc(
            color = c,
            startAngle = -90f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2, r * 2),
            style = st,
        )
    }
    drawCircle(color = c, radius = s * 0.06f, center = Offset(cx, cy))
}

/** 同心圆 */
private fun DrawScope.drawDns(s: Float, c: Color, st: Stroke) {
    val ctr = Offset(s / 2, s / 2)
    drawCircle(c, radius = s * 0.36f, center = ctr, style = st)
    drawCircle(c, radius = s * 0.16f, center = ctr, style = st)
    drawCircle(c, radius = s * 0.04f, center = ctr)
}

/** 网格/端口 */
private fun DrawScope.drawPort(s: Float, c: Color, st: Stroke) {
    for (i in 0..2) {
        val p = s * (0.20f + i * 0.30f)
        drawLine(c, Offset(s * 0.14f, p), Offset(s * 0.86f, p), strokeWidth = st.width, cap = StrokeCap.Round)
        drawLine(c, Offset(p, s * 0.14f), Offset(p, s * 0.86f), strokeWidth = st.width, cap = StrokeCap.Round)
    }
}

/** 双向箭头 */
private fun DrawScope.drawHttp(s: Float, c: Color, st: Stroke) {
    val y1 = s * 0.34f
    val y2 = s * 0.66f
    drawLine(c, Offset(s * 0.22f, y1), Offset(s * 0.78f, y1), strokeWidth = st.width, cap = StrokeCap.Round)
    drawLine(c, Offset(s * 0.62f, y1 - s * 0.10f), Offset(s * 0.78f, y1), strokeWidth = st.width, cap = StrokeCap.Round)
    drawLine(c, Offset(s * 0.62f, y1 + s * 0.10f), Offset(s * 0.78f, y1), strokeWidth = st.width, cap = StrokeCap.Round)
    drawLine(c, Offset(s * 0.78f, y2), Offset(s * 0.22f, y2), strokeWidth = st.width, cap = StrokeCap.Round)
    drawLine(c, Offset(s * 0.38f, y2 - s * 0.10f), Offset(s * 0.22f, y2), strokeWidth = st.width, cap = StrokeCap.Round)
    drawLine(c, Offset(s * 0.38f, y2 + s * 0.10f), Offset(s * 0.22f, y2), strokeWidth = st.width, cap = StrokeCap.Round)
}

/** 网线/局域网 */
private fun DrawScope.drawLan(s: Float, c: Color, st: Stroke) {
    drawCircle(c, radius = s * 0.10f, center = Offset(s * 0.50f, s * 0.22f), style = st)
    drawCircle(c, radius = s * 0.10f, center = Offset(s * 0.22f, s * 0.74f), style = st)
    drawCircle(c, radius = s * 0.10f, center = Offset(s * 0.78f, s * 0.74f), style = st)
    drawLine(c, Offset(s * 0.50f, s * 0.32f), Offset(s * 0.28f, s * 0.66f), strokeWidth = st.width)
    drawLine(c, Offset(s * 0.50f, s * 0.32f), Offset(s * 0.72f, s * 0.66f), strokeWidth = st.width)
}

/** 尖括号 */
private fun DrawScope.drawCodec(s: Float, c: Color, st: Stroke) {
    val p1 = Path().apply {
        moveTo(s * 0.40f, s * 0.26f)
        lineTo(s * 0.18f, s * 0.50f)
        lineTo(s * 0.40f, s * 0.74f)
    }
    drawPath(p1, c, style = st)
    val p2 = Path().apply {
        moveTo(s * 0.60f, s * 0.26f)
        lineTo(s * 0.82f, s * 0.50f)
        lineTo(s * 0.60f, s * 0.74f)
    }
    drawPath(p2, c, style = st)
}

/** 芯片（CPU） */
private fun DrawScope.drawCpu(s: Float, c: Color, st: Stroke) {
    drawRoundRect(
        color = c,
        topLeft = Offset(s * 0.26f, s * 0.26f),
        size = Size(s * 0.48f, s * 0.48f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.07f),
        style = st,
    )
    // 引脚
    for (i in 0..2) {
        val a = s * (0.38f + i * 0.12f)
        drawLine(c, Offset(a, s * 0.26f), Offset(a, s * 0.14f), strokeWidth = st.width)
        drawLine(c, Offset(a, s * 0.74f), Offset(a, s * 0.86f), strokeWidth = st.width)
        drawLine(c, Offset(s * 0.26f, a), Offset(s * 0.14f, a), strokeWidth = st.width)
        drawLine(c, Offset(s * 0.74f, a), Offset(s * 0.86f, a), strokeWidth = st.width)
    }
}

/** 调度：三条不同高度柱 */
private fun DrawScope.drawSched(s: Float, c: Color, st: Stroke) {
    drawLine(c, Offset(s * 0.24f, s * 0.80f), Offset(s * 0.24f, s * 0.52f), strokeWidth = st.width * 1.6f, cap = StrokeCap.Round)
    drawLine(c, Offset(s * 0.50f, s * 0.80f), Offset(s * 0.50f, s * 0.28f), strokeWidth = st.width * 1.6f, cap = StrokeCap.Round)
    drawLine(c, Offset(s * 0.76f, s * 0.80f), Offset(s * 0.76f, s * 0.42f), strokeWidth = st.width * 1.6f, cap = StrokeCap.Round)
}

/** 温度计 */
private fun DrawScope.drawThermal(s: Float, c: Color, st: Stroke) {
    drawLine(c, Offset(s * 0.50f, s * 0.18f), Offset(s * 0.50f, s * 0.60f), strokeWidth = st.width * 2.2f, cap = StrokeCap.Round)
    drawCircle(c, radius = s * 0.16f, center = Offset(s * 0.50f, s * 0.74f), style = st)
    drawCircle(c, radius = s * 0.06f, center = Offset(s * 0.50f, s * 0.74f))
}

/** 线程：三条水平线 */
private fun DrawScope.drawThread(s: Float, c: Color, st: Stroke) {
    for (i in 0 until 3) {
        val y = s * (0.30f + i * 0.20f)
        drawLine(c, Offset(s * 0.18f, y), Offset(s * 0.82f, y), strokeWidth = st.width, cap = StrokeCap.Round)
    }
}

/** 伪装：面具/眼 */
private fun DrawScope.drawSpoof(s: Float, c: Color, st: Stroke) {
    val p = Path().apply {
        moveTo(s * 0.14f, s * 0.40f)
        quadraticBezierTo(s * 0.50f, s * 0.16f, s * 0.86f, s * 0.40f)
        quadraticBezierTo(s * 0.50f, s * 0.84f, s * 0.14f, s * 0.40f)
        close()
    }
    drawPath(p, c, style = st)
    drawCircle(c, radius = s * 0.09f, center = Offset(s * 0.50f, s * 0.44f), style = st)
}

/** 盾牌（SELinux） */
private fun DrawScope.drawShield(s: Float, c: Color, st: Stroke) {
    val p = Path().apply {
        moveTo(s * 0.50f, s * 0.12f)
        lineTo(s * 0.84f, s * 0.26f)
        lineTo(s * 0.84f, s * 0.54f)
        quadraticBezierTo(s * 0.84f, s * 0.82f, s * 0.50f, s * 0.90f)
        quadraticBezierTo(s * 0.16f, s * 0.82f, s * 0.16f, s * 0.54f)
        lineTo(s * 0.16f, s * 0.26f)
        close()
    }
    drawPath(p, c, style = st)
}

/** 六边形（ELF） */
private fun DrawScope.drawElf(s: Float, c: Color, st: Stroke) {
    val p = Path()
    val cx = s / 2
    val cy = s / 2
    val r = s * 0.40f
    for (i in 0 until 6) {
        val a = Math.toRadians((60.0 * i - 90.0))
        val x = cx + (r * Math.cos(a)).toFloat()
        val y = cy + (r * Math.sin(a)).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawPath(p, c, style = st)
}
