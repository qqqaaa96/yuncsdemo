package sy.great.toolbox.tools

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 函数图像绘制（纯 Kotlin，Compose Canvas）。
 *
 * 移植自「数学教学.html」的 Plane / plot / plotPolarRad / plotParam：
 *   · 自动网格间距（step）
 *   · 坐标轴 + 箭头 + 刻度数字
 *   · 曲线带自动断点检测（近垂直/无穷处断开，不连线）
 *   · 极坐标、参数方程
 *
 * 与原版的差异：
 *   · 手势交给 Compose 的 pointerInput 处理（原版是 DOM 事件）
 *   · 坐标变换数学完全一致
 */
object CalcPlot {

    /** 视窗（世界坐标） */
    data class Viewport(
        val x0: Double = -10.0,
        val x1: Double = 10.0,
        val y0: Double = -10.0,
        val y1: Double = 10.0,
    ) {
        /** 平移（屏幕像素 → 世界偏移） */
        fun pan(dxPx: Float, dyPx: Float, w: Float, h: Float): Viewport {
            val ux = (x1 - x0) / w
            val uy = (y1 - y0) / h
            return Viewport(x0 - dxPx * ux, x1 - dxPx * ux, y0 + dyPx * uy, y1 + dyPx * uy)
        }

        /** 以某点为中心缩放 */
        fun zoomAt(cxPx: Float, cyPx: Float, factor: Float, w: Float, h: Float): Viewport {
            val cx = x0 + cxPx / w * (x1 - x0)
            val cy = y1 - cyPx / h * (y1 - y0)
            return Viewport(
                cx + (x0 - cx) * factor,
                cx + (x1 - cx) * factor,
                cy + (y0 - cy) * factor,
                cy + (y1 - cy) * factor,
            )
        }

        fun sx(x: Double, w: Float): Float = ((x - x0) / (x1 - x0) * w).toFloat()
        fun sy(y: Double, h: Float): Float = ((y1 - y) / (y1 - y0) * h).toFloat()
        fun wx(px: Float, w: Float): Double = x0 + px / w * (x1 - x0)
        fun wy(py: Float, h: Float): Double = y1 - py / h * (y1 - y0)
    }

    /** 选择一个好看的网格间距 */
    private fun stepOf(v: Viewport): Double {
        val lo = min(v.x1 - v.x0, v.y1 - v.y0) / 8.0
        val cands = doubleArrayOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0)
        for (c in cands) if (c >= lo) return c
        return 100.0
    }

    private fun fmtTick(v: Double): String {
        val r = Math.round(v * 100.0) / 100.0
        return if (abs(r - Math.round(r)) < 1e-9) Math.round(r).toString() else r.toString()
    }

    /**
     * 绘制坐标系（网格 + 轴 + 刻度）。
     * 必须在画曲线之前调用，否则曲线会被网格盖住。
     */
    fun drawAxes(
        scope: DrawScope,
        v: Viewport,
        gridColor: Color,
        axisColor: Color,
        labelColor: Color,
        bgColor: Color,
        labelTextSizePx: Float,
    ) {
        val w = scope.size.width
        val h = scope.size.height

        scope.drawRect(bgColor, Offset.Zero, Size(w, h))

        val st = stepOf(v)

        // ---- 网格 ----
        var gx = ceil(v.x0 / st) * st
        while (gx <= v.x1 + 1e-9) {
            val px = v.sx(gx, w).roundToInt() + 0.5f
            val major = abs(gx) < 1e-9
            scope.drawLine(
                color = if (major) gridColor.copy(alpha = 0.45f) else gridColor.copy(alpha = 0.16f),
                start = Offset(px, 0f),
                end = Offset(px, h),
                strokeWidth = 1f,
            )
            gx += st
        }
        var gy = ceil(v.y0 / st) * st
        while (gy <= v.y1 + 1e-9) {
            val py = v.sy(gy, h).roundToInt() + 0.5f
            val major = abs(gy) < 1e-9
            scope.drawLine(
                color = if (major) gridColor.copy(alpha = 0.45f) else gridColor.copy(alpha = 0.16f),
                start = Offset(0f, py),
                end = Offset(w, py),
                strokeWidth = 1f,
            )
            gy += st
        }

        // ---- 坐标轴 ----
        val ax = v.sx(0.0, w)
        val ay = v.sy(0.0, h)
        val hasX = v.x0 <= 0 && v.x1 >= 0
        val hasY = v.y0 <= 0 && v.y1 >= 0

        if (hasX) {
            scope.drawLine(axisColor, Offset(0f, ay), Offset(w - 2f, ay), 1.4f)
            // 箭头
            val a = Path().apply {
                moveTo(w, ay)
                lineTo(w - 8f, ay - 4.5f)
                lineTo(w - 8f, ay + 4.5f)
                close()
            }
            scope.drawPath(a, axisColor)
        }
        if (hasY) {
            scope.drawLine(axisColor, Offset(ax, h), Offset(ax, 2f), 1.4f)
            val a = Path().apply {
                moveTo(ax, 0f)
                lineTo(ax - 4.5f, 8f)
                lineTo(ax + 4.5f, 8f)
                close()
            }
            scope.drawPath(a, axisColor)
        }

        // ---- 刻度数字 ----
        val nc = scope.drawContext.canvas.nativeCanvas
        val paint = android.graphics.Paint().apply {
            color = labelColor.toArgbInt()
            textSize = labelTextSizePx
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }

        var tx = ceil(v.x0 / st) * st
        while (tx <= v.x1 + 1e-9) {
            if (abs(tx) > st * 0.01) {
                val tpx = v.sx(tx, w)
                val lab = fmtTick(tx)
                val ty = min(h - 3f, max(labelTextSizePx + 2f, ay + labelTextSizePx + 2f))
                nc.drawText(lab, tpx, ty, paint)
            }
            tx += st
        }

        var ty2 = ceil(v.y0 / st) * st
        while (ty2 <= v.y1 + 1e-9) {
            if (abs(ty2) > st * 0.01) {
                val tpy = v.sy(ty2, h)
                val lab = fmtTick(ty2)
                nc.drawText(lab, max(paint.measureText(lab) + 3f, ax - 4f), tpy + labelTextSizePx / 3f, paint)
            }
            ty2 += st
        }
    }

    /**
     * 画 y = f(x) 曲线。
     *
     * 关键：自动断点检测。
     *   · 相邻两点 y 值差超过画面高度 1.4 倍 → 断开（避免渐近线连成直线）
     *   · NaN / 无穷 → 断开
     */
    fun drawFunction(
        scope: DrawScope,
        v: Viewport,
        f: (Double) -> Double,
        color: Color,
        strokeWidth: Float = 2.2f,
    ) {
        val w = scope.size.width
        val h = scope.size.height
        val samples = max(300, (w * 1.2f).toInt())

        val path = Path()
        var penDown = false
        var prevPy = 0f
        var prevOk = false

        for (i in 0..samples) {
            val px = i.toFloat() / samples * w
            val x = v.wx(px, w)
            val y = try { f(x) } catch (_: Throwable) { Double.NaN }
            val ok = y.isFinite() && abs(y) < 1e8

            if (!ok) {
                penDown = false
                prevOk = false
                continue
            }

            val py = v.sy(y, h)

            // 断点：纵向跳变太大（渐近线/不连续）
            if (prevOk && abs(py - prevPy) > h * 1.4f) {
                penDown = false
            }

            if (!penDown) {
                path.moveTo(px, py)
                penDown = true
            } else {
                path.lineTo(px, py)
            }
            prevPy = py
            prevOk = true
        }

        scope.drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    /**
     * 极坐标曲线 r = f(theta)，theta 为弧度。
     */
    fun drawPolar(
        scope: DrawScope,
        v: Viewport,
        rf: (Double) -> Double,
        color: Color,
        strokeWidth: Float = 2.2f,
        samples: Int = 2000,
    ) {
        val w = scope.size.width
        val h = scope.size.height
        val path = Path()
        var penDown = false

        for (i in 0..samples) {
            val theta = i.toDouble() / samples * Math.PI * 2
            val r = try { rf(theta) } catch (_: Throwable) { Double.NaN }
            if (!r.isFinite() || abs(r) > 1e6) {
                penDown = false
                continue
            }
            val x = r * kotlin.math.cos(theta)
            val y = r * kotlin.math.sin(theta)
            val px = v.sx(x, w)
            val py = v.sy(y, h)
            if (!penDown) {
                path.moveTo(px, py)
                penDown = true
            } else {
                path.lineTo(px, py)
            }
        }

        scope.drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    /**
     * 参数方程 x = fx(t), y = fy(t)。
     */
    fun drawParam(
        scope: DrawScope,
        v: Viewport,
        fx: (Double) -> Double,
        fy: (Double) -> Double,
        t0: Double,
        t1: Double,
        color: Color,
        strokeWidth: Float = 2.2f,
        samples: Int = 2000,
    ) {
        val w = scope.size.width
        val h = scope.size.height
        val path = Path()
        var penDown = false

        for (i in 0..samples) {
            val t = t0 + (t1 - t0) * i / samples
            val x = try { fx(t) } catch (_: Throwable) { Double.NaN }
            val y = try { fy(t) } catch (_: Throwable) { Double.NaN }
            if (!x.isFinite() || !y.isFinite() || abs(x) > 1e6 || abs(y) > 1e6) {
                penDown = false
                continue
            }
            val px = v.sx(x, w)
            val py = v.sy(y, h)
            if (!penDown) {
                path.moveTo(px, py)
                penDown = true
            } else {
                path.lineTo(px, py)
            }
        }

        scope.drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * Color -> ARGB Int。
 *
 * 不叫 toArgb 是因为 Compose 已有同名扩展，会冲突；
 * 自定义名字避免歧义。
 */
private fun Color.toArgbInt(): Int =
    android.graphics.Color.argb(
        (alpha * 255).roundToInt(),
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
    )
