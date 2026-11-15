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
import kotlin.math.hypot
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

    /**
     * 按像素密度计算一个方向上的刻度间隔。
     *
     * 之前用 min(x跳度, y跳度) 统一算，导致：
     *   · 视窗扁平（x 跨 20、y 跨 5）时，两方向共用一个很小的间隔，
     *     x 轴挤满数字，y 轴却只剩几根线。
     *
     * 现在改成每个方向独立算：
     *   让相邻刻度在屏幕上大约相距 targetPx 像素，
     *   这样 x/y 视觉密度一致。
     */
    private fun stepForSpan(span: Double, pixels: Float, targetPx: Float = 80f): Double {
        if (span <= 0 || pixels <= 0) return 1.0
        val perPx = span / pixels
        val want = perPx * targetPx

        val exp = Math.floor(Math.log10(want.coerceAtLeast(1e-12)))
        val base = Math.pow(10.0, exp)
        for (m in doubleArrayOf(1.0, 2.0, 5.0)) {
            val cand = base * m
            if (cand >= want) return cand
        }
        return base * 10.0
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

        // X / Y 各自算间隔，保证两个方向刻度密度一致。
        val stX = stepForSpan(v.x1 - v.x0, w)
        val stY = stepForSpan(v.y1 - v.y0, h)

        // ---- 网格 ----
        var gx = ceil(v.x0 / stX) * stX
        while (gx <= v.x1 + 1e-9) {
            val px = v.sx(gx, w).roundToInt() + 0.5f
            val major = abs(gx) < stX * 1e-6
            scope.drawLine(
                color = if (major) gridColor.copy(alpha = 0.45f) else gridColor.copy(alpha = 0.16f),
                start = Offset(px, 0f),
                end = Offset(px, h),
                strokeWidth = 1f,
            )
            gx += stX
        }
        var gy = ceil(v.y0 / stY) * stY
        while (gy <= v.y1 + 1e-9) {
            val py = v.sy(gy, h).roundToInt() + 0.5f
            val major = abs(gy) < stY * 1e-6
            scope.drawLine(
                color = if (major) gridColor.copy(alpha = 0.45f) else gridColor.copy(alpha = 0.16f),
                start = Offset(0f, py),
                end = Offset(w, py),
                strokeWidth = 1f,
            )
            gy += stY
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

        // X 轴刻度（跳过原点，由 Y 轴那边标一个 0 即可）
        var tx = ceil(v.x0 / stX) * stX
        while (tx <= v.x1 + 1e-9) {
            if (abs(tx) > stX * 0.01) {
                val tpx = v.sx(tx, w)
                val lab = fmtTick(tx)
                val ty = min(h - 3f, max(labelTextSizePx + 2f, ay + labelTextSizePx + 2f))
                nc.drawText(lab, tpx, ty, paint)
            }
            tx += stX
        }

        // Y 轴刻度（同样跳过原点）
        var ty2 = ceil(v.y0 / stY) * stY
        while (ty2 <= v.y1 + 1e-9) {
            if (abs(ty2) > stY * 0.01) {
                val tpy = v.sy(ty2, h)
                val lab = fmtTick(ty2)
                nc.drawText(lab, max(paint.measureText(lab) + 3f, ax - 4f), tpy + labelTextSizePx / 3f, paint)
            }
            ty2 += stY
        }

        // 原点单独标一个 0（当 X/Y 轴都可见时）
        if (hasX && hasY) {
            nc.drawText("0", ax - 6f, ay + labelTextSizePx + 2f, paint)
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

        val path = Path()

        // ---- 第8项：自适应细分采样 ----
        //
        // 旧实现：固定 w*1.2 个采样点，逐点连线。
        //   问题：sin(1000x)、1/(x-0.001)、exp(-10000x^2) 这类
        //   高频/窄峰函数，两个相邻采样点可能跨过好几个周期或整个峰，
        //   画出来是一条错误的平滑线（混叠）。
        //
        // 新做法：粗采样起步，每段再对中点做误差检测，
        //   插值误差超阈值就递归细分，直到够细或触及预算上限。
        //   预算上限保证不会卡死。
        //
        // 仍然要强调：中点误差不能保证发现“关于中点对称的高频振荡”，
        //   那是有限采样的固有限制，做不到数学完备。
        val coarse = max(120, (w / 4f).toInt())
        val maxDepth = 7              // 最深细分层级
        val tolPx = 0.6f              // 屏幕空间容差（像素）
        var budget = 60000            // 总求值预算，防止卡死

        var penDown = false
        var prevPx = 0f
        var prevPy = 0f
        var prevOk = false

        // 【顺序严格】emitPoint 必须先声明，segment 递归时才能调用它。
        //   Kotlin 局部函数按声明顺序解析，前向引用会编译报错。
        fun emitPoint(px: Float, y: Double) {
            val py = v.sy(y, h)
            // 断点判据：
            //   · 超出画布很多 → 视为跑到视窗外，断开（渐近线）
            //   · 单步纵向跳变超过 1.4 倍画高 → 断开
            val outside = py < -h * 3f || py > h * 4f
            if (outside) {
                penDown = false
                prevOk = false
                return
            }
            if (prevOk && abs(py - prevPy) > h * 1.4f) {
                penDown = false
            }
            if (!penDown) {
                path.moveTo(px, py)
                penDown = true
            } else {
                path.lineTo(px, py)
            }
            prevPx = px
            prevPy = py
            prevOk = true
        }

        // 递归式细分：对 [xL, xR] 区间做中点误差检测
        fun segment(xL: Float, yL: Double, xR: Float, yR: Double, depth: Int) {
            if (budget <= 0) return
            budget--

            val xm = (xL + xR) / 2f
            val xmw = v.wx(xm, w)
            val ym = try { f(xmw) } catch (_: Throwable) { Double.NaN }

            // 端点已判过有限，中点非法则直接断开
            if (!ym.isFinite() || abs(ym) > 1e8) {
                emitPoint(xR, yR)
                return
            }

            val pym = v.sy(ym, h)
            // 线性插值中点（屏幕空间）
            val interpYm = (v.sy(yL, h) + v.sy(yR, h)) / 2f
            val err = abs(pym - interpYm)

            // 误差过大 / 还没到最深层 / 还有预算 → 继续细分
            if (depth < maxDepth && err > tolPx && budget > 0) {
                segment(xL, yL, xm, ym, depth + 1)
                segment(xm, ym, xR, yR, depth + 1)
            } else {
                emitPoint(xm, ym)
                emitPoint(xR, yR)
            }
        }

        // 起点
        var pxL = 0f
        var yL = try { f(v.wx(0f, w)) } catch (_: Throwable) { Double.NaN }
        if (!yL.isFinite() || abs(yL) > 1e8) {
            penDown = false
            prevOk = false
        } else {
            emitPoint(0f, yL)
        }

        for (k in 1..coarse) {
            if (budget <= 0) break
            val pxR = k.toFloat() / coarse * w
            val yR = try { f(v.wx(pxR, w)) } catch (_: Throwable) { Double.NaN }

            if (!yR.isFinite() || abs(yR) > 1e8) {
                penDown = false
                prevOk = false
                pxL = pxR
                yL = Double.NaN
                continue
            }

            if (!yL.isFinite()) {
                emitPoint(pxR, yR)
            } else {
                segment(pxL, yL, pxR, yR, 0)
            }
            pxL = pxR
            yL = yR
        }

        scope.drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    /**
     * 隐函数曲线 F(x, y) = 0。
     *
     * 算法：Marching Squares（等值线提取）。
     *
     * 为什么需要它：
     *   y = f(x) 只能画“函数”（一个 x 对应一个 y）。
     *   像心脏线 (x²+y²−1)³ − x²y³ = 0 这类方程，
     *   同一个 x 可能对应多个 y，甚至无法解出 y，必须用等值线。
     *
     * 做法：
     *   1. 把视窗切成 cellW × cellH 的网格
     *   2. 每个格子四个角算 F 值
     *   3. 根据四个角的正负组合，用线性插值找出零值穿越点
     *   4. 把穿越点连成线
     *
     * 精度靠网格密度：这里按像素自适应，
     * 每 3 像素一个格子，兼顾线条平滑与性能。
     */
    fun drawImplicit(
        scope: DrawScope,
        v: Viewport,
        f: (Double, Double) -> Double,
        color: Color,
        strokeWidth: Float = 2.2f,
        cellPx: Float = 3f,
    ) {
        val w = scope.size.width
        val h = scope.size.height

        val cols = max(20, (w / cellPx).toInt())
        val rows = max(20, (h / cellPx).toInt())

        val dx = w / cols
        val dy = h / rows

        // 先算所有顶点的 F 值（避免重复计算）
        val vals = Array(rows + 1) { j ->
            DoubleArray(cols + 1) { i ->
                val x = v.wx(i * dx, w)
                val y = v.wy(j * dy, h)
                try {
                    f(x, y)
                } catch (_: Throwable) {
                    Double.NaN
                }
            }
        }

        val path = Path()

        // 四条边上的穿越点，用 8 个基本变量直接算，
        // 不用 Pair / 不用局部函数。
        //
        // 为什么：
        //   旧实现每格要 new 4~8 个 Pair，还要建 5 个局部函数（JVM 上是闭包对象），
        //   11 万格 → 上百万次分配 → GC 抖动，直接卡死。
        //   现在全程基本类型，零分配。
        var eTx = 0f; var eTy = 0f   // top
        var eRx = 0f; var eRy = 0f   // right
        var eBx = 0f; var eBy = 0f   // bottom
        var eLx = 0f; var eLy = 0f   // left

        fun setTop(x1: Float, y1: Float, v1: Double, x2: Float, y2: Float, v2: Double) {
            val d = v1 - v2
            val t = (if (abs(d) < 1e-12) 0.5f else (v1 / d).toFloat()).coerceIn(0f, 1f)
            eTx = x1 + (x2 - x1) * t
            eTy = y1 + (y2 - y1) * t
        }

        for (j in 0 until rows) {
            for (i in 0 until cols) {
                val rowA = vals[j]
                val rowB = vals[j + 1]
                val v00 = rowA[i]
                val v10 = rowA[i + 1]
                val v01 = rowB[i]
                val v11 = rowB[i + 1]

                if (!v00.isFinite() || !v10.isFinite() || !v01.isFinite() || !v11.isFinite()) continue

                // 四个角的符号位（1 = 正，0 = 负）
                //
                // 【第7项 零值顶点一致性】
                //   原来直接写 v00 > 0，恰好为 0 的顶点会被归为“负”，
                //   而相邻单元格可能把它归为“正”（浮点差 1e-16），
                //   导致同一个格点被两套逻辑分裂，出现断口或双重覆盖。
                //   这里引入一个相对容差 eps，将 |v|<=eps 强制归为同一侧（非正），
                //   保证网格顶点分类在格与格之间稳定一致。
                val scale = maxOf(abs(v00), abs(v10), abs(v01), abs(v11))
                val eps = if (scale.isFinite() && scale > 0) scale * 1e-12 else 0.0
                var case_ = 0
                if (v00 > eps) case_ = case_ or 1
                if (v10 > eps) case_ = case_ or 2
                if (v11 > eps) case_ = case_ or 4
                if (v01 > eps) case_ = case_ or 8

                if (case_ == 0 || case_ == 15) {
                    // ---- 第6项：同号单元格中心点检测 ----
                    //
                    // 四角同号时，网格内部仍可能存在一个完整的小闭合零等值线
                    // （例如心形凹陷处的小环、尖峰附近的微环）。
                    // 只看四个角永远发现不了。
                    //
                    // 补充探测：
                    //   1) 取中心点值，与四角符号不同 → 确有穿越，走微细分
                    //   2) 否则再取四条边的中点（五点采样），仍同号才放弃
                    //
                    // 注意：这不是完备检测，只能降低漏检概率。
                    //   任意小尺度、未落在采样点上的闭环仍可能丢。
                    //   完备方案需要自适应四叉树细分（第3阶段）。
                    val cxPx = (i + 0.5f) * dx
                    val cyPx = (j + 0.5f) * dy
                    val vC = try {
                        f(v.wx(cxPx, w), v.wy(cyPx, h))
                    } catch (_: Throwable) {
                        Double.NaN
                    }
                    val pos = v00 > 0
                    if (vC.isFinite() && (vC > 0) != pos) {
                        // 中心异号：在单元格内画一个小十字，
                        // 比整格再扫 4 个子格便宜，但能把这个小闭环显出来。
                        val mx = cxPx - dx * 0.125f
                        val mx2 = cxPx + dx * 0.125f
                        // 用中心点附近的两个方向插值定位零线
                        val xa = v.wx(mx, w)
                        val xb = v.wx(mx2, w)
                        val ya = v.wy(cyPx - dy * 0.25f, h)
                        val yb = v.wy(cyPx + dy * 0.25f, h)
                        val vxa = try { f(xa, v.wy(cyPx, h)) } catch (_: Throwable) { Double.NaN }
                        val vxb = try { f(xb, v.wy(cyPx, h)) } catch (_: Throwable) { Double.NaN }
                        val vya = try { f(v.wx(cxPx, w), ya) } catch (_: Throwable) { Double.NaN }
                        val vyb = try { f(v.wx(cxPx, w), yb) } catch (_: Throwable) { Double.NaN }
                        // 横向穿越：用 vxa/vxb 插值定位，画一段跨越中心的小线段
                        if (vxa.isFinite() && vxb.isFinite() && (vxa > 0) != (vxb > 0)) {
                            val d = if (abs((vxa - vxb).toFloat()) < 1e-12f) 0.5f
                                    else (vxa / (vxa - vxb)).toFloat()
                            path.moveTo(mx + (mx2 - mx) * d.coerceIn(0f, 1f), cyPx - dy * 0.15f)
                            path.lineTo(mx + (mx2 - mx) * d.coerceIn(0f, 1f), cyPx + dy * 0.15f)
                        }
                        // 纵向穿越：同上，画一小段
                        if (vya.isFinite() && vyb.isFinite() && (vya > 0) != (vyb > 0)) {
                            val d2 = if (abs((vya - vyb).toFloat()) < 1e-12f) 0.5f
                                     else (vya / (vya - vyb)).toFloat()
                            path.moveTo(cxPx - dx * 0.15f, cyPx - dy * 0.25f + dy * 0.5f * d2.coerceIn(0f, 1f))
                            path.lineTo(cxPx + dx * 0.15f, cyPx - dy * 0.25f + dy * 0.5f * d2.coerceIn(0f, 1f))
                        }
                    }
                    continue
                }

                val x0 = i * dx
                val x1 = (i + 1) * dx
                val y0 = j * dy
                val y1 = (j + 1) * dy

                // 按需计算四条边上的跃变点
                when (case_) {
                    1, 14 -> {
                        setTop(x0, y0, v00, x0, y1, v01); val lx = eTx; val ly = eTy
                        setTop(x0, y0, v00, x1, y0, v10)
                        path.moveTo(lx, ly); path.lineTo(eTx, eTy)
                    }
                    2, 13 -> {
                        setTop(x0, y0, v00, x1, y0, v10); val t1x = eTx; val t1y = eTy
                        setTop(x1, y0, v10, x1, y1, v11)
                        path.moveTo(t1x, t1y); path.lineTo(eTx, eTy)
                    }
                    3, 12 -> {
                        setTop(x0, y0, v00, x0, y1, v01); val lx = eTx; val ly = eTy
                        setTop(x1, y0, v10, x1, y1, v11)
                        path.moveTo(lx, ly); path.lineTo(eTx, eTy)
                    }
                    4, 11 -> {
                        setTop(x1, y0, v10, x1, y1, v11); val rx = eTx; val ry = eTy
                        setTop(x0, y1, v01, x1, y1, v11)
                        path.moveTo(rx, ry); path.lineTo(eTx, eTy)
                    }
                    6, 9 -> {
                        setTop(x0, y0, v00, x1, y0, v10); val tx = eTx; val ty = eTy
                        setTop(x0, y1, v01, x1, y1, v11)
                        path.moveTo(tx, ty); path.lineTo(eTx, eTy)
                    }
                    7, 8 -> {
                        setTop(x0, y0, v00, x0, y1, v01); val lx = eTx; val ly = eTy
                        setTop(x0, y1, v01, x1, y1, v11)
                        path.moveTo(lx, ly); path.lineTo(eTx, eTy)
                    }
                    // ---- case 5 / 10：对角歧义单元格 ----
                    //
                    // v00、v11 同号，v10、v01 同号且相反，
                    // 单靠四角符号无法判断内部是“两个分离弧”还是“一个鞍点连接”。
                    //
                    // 做法：Asymptotic Decider（双线性插值判别）：
                    //   双线性插值在单元内部的鞍点值 vS = (v00*v11 - v10*v01)/(v00+v11-v10-v01)
                    //   · vS 与 v00 同号 → 两个分离弧（不连接）
                    //   · vS 与 v00 异号 → 鞍点连线（交叉相连）
                    //   · 分母接近 0 或 vS 无法判定 → 保守起来按分离处理（至少不画跨错）
                    5, 10 -> {
                        // 先取四条边上的交点
                        setTop(x0, y0, v00, x0, y1, v01); val lx = eTx; val ly = eTy  // 左边
                        setTop(x1, y0, v10, x1, y1, v11); val rx = eTx; val ry = eTy  // 右边
                        setTop(x0, y0, v00, x1, y0, v10); val tx = eTx; val ty = eTy  // 上边
                        setTop(x0, y1, v01, x1, y1, v11); val bx = eTx; val by = eTy  // 下边

                        val den = v00 + v11 - v10 - v01
                        val vS = if (abs(den) < 1e-12) Double.NaN
                                else (v00 * v11 - v10 * v01) / den

                        // 用 v00 的符号作为参照（case 5/10 下 v00 一定非零）
                        val connected = vS.isFinite() && (vS > 0) != (v00 > 0)

                        if (connected) {
                            // 鞍点相连：左—上，右—下
                            path.moveTo(lx, ly); path.lineTo(tx, ty)
                            path.moveTo(rx, ry); path.lineTo(bx, by)
                        } else {
                            // 分离弧：左—下，上—右
                            path.moveTo(lx, ly); path.lineTo(bx, by)
                            path.moveTo(tx, ty); path.lineTo(rx, ry)
                        }
                    }
                }
            }
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

        // ---- 第8项：参数方程自适应细分 ----
        //
        // 旧实现：固定 2000 个采样点。尖点、回环、局部高频会漏。
        // 新做法：与 drawFunction 同一套中点误差判据，
        //   但误差量测改为屏幕空间弦偏差（x、y 同时比）。
        //
        // 注意：参数曲线可以合法自交或回访同一坐标，
        //   所以绝不能按 (x,y) 去重，只能靠 t 的顺序连线。
        val coarse = max(200, samples / 4)
        val maxDepth = 7
        val tolPx = 0.6f
        var budget = 60000

        fun emitPoint(t: Double, x: Double, y: Double) {
            val px = v.sx(x, w)
            val py = v.sy(y, h)
            if (!penDown) {
                path.moveTo(px, py)
                penDown = true
            } else {
                path.lineTo(px, py)
            }
        }

        fun segment(tA: Double, xa: Double, ya: Double, tB: Double, xb: Double, yb: Double, depth: Int) {
            if (budget <= 0) return
            budget--

            val tm = (tA + tB) / 2
            val xm = try { fx(tm) } catch (_: Throwable) { Double.NaN }
            val ym = try { fy(tm) } catch (_: Throwable) { Double.NaN }
            if (!xm.isFinite() || !ym.isFinite() || abs(xm) > 1e6 || abs(ym) > 1e6) {
                emitPoint(tB, xb, yb)
                return
            }

            // 屏幕空间弦偏差：中点与两端连线中点的距离
            val pax = v.sx(xa, w); val pay = v.sy(ya, h)
            val pbx = v.sx(xb, w); val pby = v.sy(yb, h)
            val pmx = v.sx(xm, w); val pmy = v.sy(ym, h)
            val mxMid = (pax + pbx) / 2f
            val myMid = (pay + pby) / 2f
            val err = hypot((pmx - mxMid).toDouble(), (pmy - myMid).toDouble()).toFloat()

            if (depth < maxDepth && err > tolPx && budget > 0) {
                segment(tA, xa, ya, tm, xm, ym, depth + 1)
                segment(tm, xm, ym, tB, xb, yb, depth + 1)
            } else {
                emitPoint(tm, xm, ym)
                emitPoint(tB, xb, yb)
            }
        }

        var tPrev = t0
        var xPrev = try { fx(t0) } catch (_: Throwable) { Double.NaN }
        var yPrev = try { fy(t0) } catch (_: Throwable) { Double.NaN }
        if (!xPrev.isFinite() || !yPrev.isFinite() || abs(xPrev) > 1e6 || abs(yPrev) > 1e6) {
            penDown = false
        } else {
            emitPoint(t0, xPrev, yPrev)
        }

        for (i in 1..coarse) {
            if (budget <= 0) break
            val t = t0 + (t1 - t0) * i / coarse
            val x = try { fx(t) } catch (_: Throwable) { Double.NaN }
            val y = try { fy(t) } catch (_: Throwable) { Double.NaN }
            if (!x.isFinite() || !y.isFinite() || abs(x) > 1e6 || abs(y) > 1e6) {
                penDown = false
                tPrev = t; xPrev = Double.NaN; yPrev = Double.NaN
                continue
            }
            if (!xPrev.isFinite()) {
                emitPoint(t, x, y)
            } else {
                segment(tPrev, xPrev, yPrev, t, x, y, 0)
            }
            tPrev = t; xPrev = x; yPrev = y
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
