package sy.great.toolbox.ui

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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 底栏 Tab 图标（矢量绘制，不用 Emoji）。
 *
 * 统一 22dp 画布 / 细线条 / 圆角线帽，与项目已有 FeatureIcon 风格一致。
 *
 * 图标语义：
 *   home       —— 房子（屋顶 + 主体）
 *   anticheat  —— 盾牌（带勾）
 *   features   —— 四宫格
 *   tools      —— 齿轮（简化：六边形 + 中心圆）
 *   profile    —— 人形（头 + 肩）
 */
@Composable
fun TabGlyph(
    kind: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.085f
        val st = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val pad = s * 0.14f
        val left = pad
        val top = pad
        val right = s - pad
        val bottom = s - pad
        val cw = right - left
        val ch = bottom - top
        val cx = s / 2f
        val cy = s / 2f

        when (kind) {
            // ---- 房子 ----
            "home" -> {
                // 屋顶：从左上斜到顶，再斜到右上
                val roof = Path().apply {
                    moveTo(left, top + ch * 0.42f)
                    lineTo(cx, top)
                    lineTo(right, top + ch * 0.42f)
                }
                drawPath(roof, color, style = st)
                // 主体
                val body = Path().apply {
                    moveTo(left + cw * 0.12f, top + ch * 0.40f)
                    lineTo(left + cw * 0.12f, bottom)
                    lineTo(right - cw * 0.12f, bottom)
                    lineTo(right - cw * 0.12f, top + ch * 0.40f)
                }
                drawPath(body, color, style = st)
                // 门
                drawLine(
                    color,
                    Offset(cx - cw * 0.10f, bottom),
                    Offset(cx - cw * 0.10f, bottom - ch * 0.24f),
                    strokeWidth = w, cap = StrokeCap.Round
                )
                drawLine(
                    color,
                    Offset(cx + cw * 0.10f, bottom),
                    Offset(cx + cw * 0.10f, bottom - ch * 0.24f),
                    strokeWidth = w, cap = StrokeCap.Round
                )
                drawLine(
                    color,
                    Offset(cx - cw * 0.10f, bottom - ch * 0.24f),
                    Offset(cx + cw * 0.10f, bottom - ch * 0.24f),
                    strokeWidth = w, cap = StrokeCap.Round
                )
            }

            // ---- 盾牌 ----
            "anticheat" -> {
                val shield = Path().apply {
                    moveTo(cx, top)
                    lineTo(right, top + ch * 0.20f)
                    lineTo(right, top + ch * 0.50f)
                    // 底部收成尖角
                    cubicTo(
                        right, bottom - ch * 0.10f,
                        cx + cw * 0.18f, bottom,
                        cx, bottom
                    )
                    cubicTo(
                        cx - cw * 0.18f, bottom,
                        left, bottom - ch * 0.10f,
                        left, top + ch * 0.50f
                    )
                    lineTo(left, top + ch * 0.20f)
                    close()
                }
                drawPath(shield, color, style = st)
                // 内部的勾
                val tick = Path().apply {
                    moveTo(cx - cw * 0.18f, cy)
                    lineTo(cx - cw * 0.03f, cy + ch * 0.16f)
                    lineTo(cx + cw * 0.20f, cy - ch * 0.16f)
                }
                drawPath(tick, color, style = st)
            }

            // ---- 四宫格 ----
            "features" -> {
                val gap = cw * 0.14f
                val box = (cw - gap) / 2f
                val r = s * 0.06f
                drawRoundRect(
                    color, Offset(left, top), Size(box, box),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r), style = st
                )
                drawRoundRect(
                    color, Offset(left + box + gap, top), Size(box, box),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r), style = st
                )
                drawRoundRect(
                    color, Offset(left, top + box + gap), Size(box, box),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r), style = st
                )
                drawRoundRect(
                    color, Offset(left + box + gap, top + box + gap), Size(box, box),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r), style = st
                )
            }

            // ---- 齿轮（六边形 + 中心圆）----
            "tools" -> {
                val outer = s * 0.40f
                val inner = s * 0.16f
                val gear = Path()
                // 六边形
                for (i in 0 until 6) {
                    val ang = Math.toRadians((60.0 * i - 90.0))
                    val x = cx + (outer * Math.cos(ang)).toFloat()
                    val y = cy + (outer * Math.sin(ang)).toFloat()
                    if (i == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
                }
                gear.close()
                drawPath(gear, color, style = st)
                drawCircle(color, radius = inner, center = Offset(cx, cy), style = st)
            }

            // ---- 人形 ----
            "profile" -> {
                // 头
                drawCircle(
                    color,
                    radius = cw * 0.19f,
                    center = Offset(cx, top + ch * 0.26f),
                    style = st
                )
                // 肩（半圆弧）
                val shoulder = Path().apply {
                    moveTo(left + cw * 0.08f, bottom)
                    cubicTo(
                        left + cw * 0.10f, bottom - ch * 0.34f,
                        right - cw * 0.10f, bottom - ch * 0.34f,
                        right - cw * 0.08f, bottom
                    )
                }
                drawPath(shoulder, color, style = st)
            }

            // ---- 信封（邮件）----
            // 外框 + 上部 V 形折痕，标准邮件语义，线条与底栏其他图标一致。
            "mail" -> {
                // 信封主体：略带高度比的圆角矩形
                val bodyTop = top + ch * 0.16f
                val bodyBottom = bottom - ch * 0.16f
                val r = cw * 0.07f

                val rect = Path().apply {
                    moveTo(left + r, bodyTop)
                    lineTo(right - r, bodyTop)
                    quadraticTo(right, bodyTop, right, bodyTop + r)
                    lineTo(right, bodyBottom - r)
                    quadraticTo(right, bodyBottom, right - r, bodyBottom)
                    lineTo(left + r, bodyBottom)
                    quadraticTo(left, bodyBottom, left, bodyBottom - r)
                    lineTo(left, bodyTop + r)
                    quadraticTo(left, bodyTop, left + r, bodyTop)
                    close()
                }
                drawPath(rect, color, style = st)

                // 封口折痕：从左中到中点再到右中
                val flap = Path().apply {
                    moveTo(left, bodyTop + ch * 0.02f)
                    lineTo(cx, bodyTop + ch * 0.34f)
                    lineTo(right, bodyTop + ch * 0.02f)
                }
                drawPath(flap, color, style = st)
            }

            // 未知：画个圆点兜底
            else -> drawCircle(color, radius = s * 0.12f, center = Offset(cx, cy), style = st)
        }
    }
}
