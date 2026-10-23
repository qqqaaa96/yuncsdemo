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
 * 卡片标题图标（矢量绘制，不用 Emoji）。
 *
 * 与 TabGlyph 同一套参数：22dp 画布、细线条、圆角线帽。
 *
 * 为什么要自己画：
 *   之前用 Unicode 字符（如 \u26A1 闪电）当图标，
 *   但 emoji 字符在不同 ROM 上会被渲染成彩色 emoji，
 *   与整体线条风格完全不符。而且颜色也不能跟随主题。
 *
 * 图标语义：
 *   battery  —— 电池（带闪电，电量伪装）
 *   settings —— 齿轮（当前属性）
 *   chip     —— 芯片（选择芯片）
 *   phone    —— 手机（选择机型）
 *   edit     —— 铅笔（自定义）
 *   info     —— 圆圈 i（说明）
 *   list     —— 三条横线（运行参数）
 */
@Composable
fun CardGlyph(
    kind: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
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
            // ---- 电池 + 闪电 ----
            "battery" -> {
                // 电池外壳（横向矩形，右侧留正极凸起）
                val bodyTop = top + ch * 0.24f
                val bodyBottom = bottom - ch * 0.24f
                val bodyRight = right - cw * 0.10f
                val r = cw * 0.07f

                val shell = Path().apply {
                    moveTo(left + r, bodyTop)
                    lineTo(bodyRight - r, bodyTop)
                    quadraticTo(bodyRight, bodyTop, bodyRight, bodyTop + r)
                    lineTo(bodyRight, bodyBottom - r)
                    quadraticTo(bodyRight, bodyBottom, bodyRight - r, bodyBottom)
                    lineTo(left + r, bodyBottom)
                    quadraticTo(left, bodyBottom, left, bodyBottom - r)
                    lineTo(left, bodyTop + r)
                    quadraticTo(left, bodyTop, left + r, bodyTop)
                    close()
                }
                drawPath(shell, color, style = st)

                // 正极
                drawLine(
                    color,
                    Offset(bodyRight, cy - ch * 0.14f),
                    Offset(right, cy - ch * 0.14f),
                    strokeWidth = w, cap = StrokeCap.Round
                )
                drawLine(
                    color,
                    Offset(right, cy - ch * 0.14f),
                    Offset(right, cy + ch * 0.14f),
                    strokeWidth = w, cap = StrokeCap.Round
                )
                drawLine(
                    color,
                    Offset(right, cy + ch * 0.14f),
                    Offset(bodyRight, cy + ch * 0.14f),
                    strokeWidth = w, cap = StrokeCap.Round
                )

                // 闪电
                val bcx = left + cw * 0.42f
                val bolt = Path().apply {
                    moveTo(bcx + cw * 0.07f, bodyTop + ch * 0.10f)
                    lineTo(bcx - cw * 0.07f, cy)
                    lineTo(bcx + cw * 0.02f, cy)
                    lineTo(bcx - cw * 0.06f, bodyBottom - ch * 0.10f)
                }
                drawPath(bolt, color, style = st)
            }

            // ---- 齿轮 ----
            "settings" -> {
                val outer = cw * 0.40f
                val inner = cw * 0.16f
                // 外圈 + 八个齿
                val teeth = 8
                val path = Path()
                for (i in 0 until teeth * 2) {
                    val ang = Math.PI * i / teeth - Math.PI / 2
                    val rad = if (i % 2 == 0) outer else outer * 0.72f
                    val x = cx + (Math.cos(ang) * rad).toFloat()
                    val y = cy + (Math.sin(ang) * rad).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, color, style = st)
                drawCircle(color, radius = inner, center = Offset(cx, cy), style = st)
            }

            // ---- 芯片 ----
            "chip" -> {
                val chipSize = cw * 0.52f
                val l = cx - chipSize / 2
                val t = cy - chipSize / 2
                val rgt = cx + chipSize / 2
                val b = cy + chipSize / 2

                // 方块
                drawRect(
                    color,
                    topLeft = Offset(l, t),
                    size = Size(chipSize, chipSize),
                    style = st
                )
                // 内方块
                val ins = chipSize * 0.26f
                drawRect(
                    color,
                    topLeft = Offset(cx - ins / 2, cy - ins / 2),
                    size = Size(ins, ins),
                    style = st
                )
                // 四边引脚（每边 3 根）
                // 上下
                for (i in 0..2) {
                    val px = l + chipSize * (0.25f + i * 0.25f)
                    drawLine(color, Offset(px, t), Offset(px, top), strokeWidth = w, cap = StrokeCap.Round)
                    drawLine(color, Offset(px, b), Offset(px, bottom), strokeWidth = w, cap = StrokeCap.Round)
                }
                // 左右
                for (i in 0..2) {
                    val py = t + chipSize * (0.25f + i * 0.25f)
                    drawLine(color, Offset(l, py), Offset(left, py), strokeWidth = w, cap = StrokeCap.Round)
                    drawLine(color, Offset(rgt, py), Offset(right, py), strokeWidth = w, cap = StrokeCap.Round)
                }
            }

            // ---- 手机 ----
            "phone" -> {
                val r = cw * 0.14f
                val bodyL = cx - cw * 0.24f
                val bodyR = cx + cw * 0.24f
                val path = Path().apply {
                    moveTo(bodyL + r, top)
                    lineTo(bodyR - r, top)
                    quadraticTo(bodyR, top, bodyR, top + r)
                    lineTo(bodyR, bottom - r)
                    quadraticTo(bodyR, bottom, bodyR - r, bottom)
                    lineTo(bodyL + r, bottom)
                    quadraticTo(bodyL, bottom, bodyL, bottom - r)
                    lineTo(bodyL, top + r)
                    quadraticTo(bodyL, top, bodyL + r, top)
                    close()
                }
                drawPath(path, color, style = st)
                // 听筒
                drawLine(
                    color,
                    Offset(cx - cw * 0.06f, top + ch * 0.10f),
                    Offset(cx + cw * 0.06f, top + ch * 0.10f),
                    strokeWidth = w, cap = StrokeCap.Round
                )
                // Home 键
                drawCircle(
                    color,
                    radius = cw * 0.045f,
                    center = Offset(cx, bottom - ch * 0.09f),
                    style = st
                )
            }

            // ---- 铅笔 ----
            "edit" -> {
                // 斜放的笔身
                val p1 = Offset(left + cw * 0.06f, bottom - ch * 0.06f)
                val p2 = Offset(right - cw * 0.06f, top + ch * 0.06f)

                // 笔尖三角
                val tip = Path().apply {
                    moveTo(p1.x, p1.y)
                    lineTo(p1.x + cw * 0.16f, p1.y - ch * 0.02f)
                    lineTo(p1.x + cw * 0.02f, p1.y - ch * 0.16f)
                    close()
                }
                drawPath(tip, color, style = st)

                // 笔杆（两条平行线）
                val dx = (p2.x - p1.x) * 0.10f
                val dy = (p2.y - p1.y) * 0.10f
                drawLine(
                    color,
                    Offset(p1.x + dx, p1.y - ch * 0.14f),
                    Offset(p2.x + dx * 0.6f, p2.y),
                    strokeWidth = w, cap = StrokeCap.Round
                )
                drawLine(
                    color,
                    Offset(p1.x + cw * 0.14f, p1.y - dy),
                    Offset(p2.x + cw * 0.04f, p2.y + ch * 0.06f),
                    strokeWidth = w, cap = StrokeCap.Round
                )
            }

            // ---- 信息 ----
            "info" -> {
                drawCircle(color, radius = cw * 0.44f, center = Offset(cx, cy), style = st)
                // i 的点
                drawCircle(
                    color,
                    radius = w * 0.62f,
                    center = Offset(cx, cy - ch * 0.20f)
                )
                // i 的竖
                drawLine(
                    color,
                    Offset(cx, cy - ch * 0.04f),
                    Offset(cx, cy + ch * 0.22f),
                    strokeWidth = w, cap = StrokeCap.Round
                )
            }

            // ---- 列表 ----
            "list" -> {
                val gap = ch * 0.28f
                for (i in 0..2) {
                    val y = cy - gap + i * gap
                    drawLine(
                        color,
                        Offset(left, y),
                        Offset(right, y),
                        strokeWidth = w, cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}
