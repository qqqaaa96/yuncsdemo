package sy.great.toolbox.ui.calculator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.tools.CalcEngine
import sy.great.toolbox.tools.CalcFormat
import sy.great.toolbox.tools.CalcPlot
import sy.great.toolbox.ui.theme.LocalPalette
import kotlin.math.abs
import kotlin.math.roundToInt

/** 坐标类型 */
private enum class GraphMode(val label: String) {
    CART("y=f(x)"),
    POLAR("r=f(θ)"),
    PARAM("参数方程"),
}

/**
 * 函数图像计算器。
 *
 * 支持：
 *   · 直角坐标 y = f(x)
 *   · 极坐标 r = f(theta)
 *   · 参数方程 x=fx(t), y=fy(t)
 *
 * 符号自适应（全部自动，用户不用管）：
 *   · 中文括号 （） 【】 〖〗 「」 → 半角 ( )
 *   · 全角字符 → 半角
 *   · × ÷ − → * / -
 *   · 上标 ²³⁴ → ^2 ^3 ^4
 *   · 中文逗号、句号 → , .
 *   · 空格全部抹掉
 *   · θ π √ → theta pi sqrt
 *
 * 变量自动检测：
 *   写 sin(x)、r=1-sin(theta)、cos(t) 都能识别，
 *   优先级 theta > t > x > y > r > n。
 *
 * 滑块（自适应上限）：
 *   滑块控制自变量 x 的取值范围，不是固定 100。
 *   上限由函数实际形态决定：
 *     · 如果函数在 [−10,10] 内就把视窗填满了 → 上限小就行
 *     · 如果函数变化很平缓、怎么画都稀疏 → 上限调大
 *   具体算法见 computeSliderMax()。
 */
@Composable
fun GraphCalc() {
    val pal = LocalPalette.current

    var mode by remember { mutableStateOf(GraphMode.CART) }
    var input by remember { mutableStateOf("sin(x)") }
    var inputX by remember { mutableStateOf("5*cos(t)") }
    var inputY by remember { mutableStateOf("5*sin(t)") }
    var tRange by remember { mutableStateOf("0, 6.2832") }
    var xRange by remember { mutableStateOf("-10, 10") }
    var yRange by remember { mutableStateOf("-10, 10") }
    var error by remember { mutableStateOf("") }

    // 当前视窗
    var viewport by remember { mutableStateOf(CalcPlot.Viewport(-10.0, 10.0, -10.0, 10.0)) }

    // 编译结果
    var fn1 by remember { mutableStateOf<((Double) -> Double)?>(null) }
    var fn2 by remember { mutableStateOf<((Double) -> Double)?>(null) }

    // 自变量名（自动检测）
    var varName by remember { mutableStateOf("x") }
    // 参数列表（a、b、k…）与它们的值
    var paramNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var paramValues by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }

    // 编译好的 AST（带参求值用）
    var ast by remember { mutableStateOf<CalcEngine.Node?>(null) }
    var astY by remember { mutableStateOf<CalcEngine.Node?>(null) }

    // 滑块：当前值 + 上限（自适应）
    var sliderMax by remember { mutableStateOf(100f) }
    var sliderValue by remember { mutableStateOf(100f) }

    /** 解析范围字符串 "min,max" */
    fun parseRange(s: String, d0: Double, d1: Double): Pair<Double, Double> {
        val p = s.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        return (p.getOrNull(0) ?: d0) to (p.getOrNull(1) ?: d1)
    }

    /**
     * 计算滑块上限。
     *
     * 思路：
     *   滑块控制“自变量取样范围”（单位是函数自变量的倍数），
     *   上限要让用户拉到中间时就能看出合适密度。
     *
     * 用函数形态判断：
     *   1. 用当前区间采样，得到斜率中位数 slope
     *   2. slope 越小 → 变化越平缓 → 需要更大的范围才看得出变化 → 上限大
     *   3. slope 越大 → 变化很剧烈 → 小范围就撑爆 → 上限小
     *
     * 最后夹到 [10, 1000]，避免极端值。
     */
    fun computeSliderMax(f: (Double) -> Double, x0: Double, x1: Double): Float {
        val shape = CalcEngine.analyzeShape(f, x0, x1, 400)
        if (shape.validRatio <= 0.0) return 100f

        val span = (x1 - x0).let { if (abs(it) < 1e-9) 20.0 else abs(it) }
        val ySpan = (shape.maxY - shape.minY)

        // 斜率相对于值域的大小：越小越平缓
        val rel = if (ySpan < 1e-9) 0.0 else shape.medianSlope / ySpan

        // 基础倍数：区间宽度本身
        val base = span

        // 平缓（rel 小）→ 乘大系数；剧烈（rel 大）→ 乘小系数
        // rel 典型值：sin(x) 在 [-10,10] 约 0.01；tan 类会大很多
        val factor = when {
            rel < 0.002 -> 12.0   // 极平缓，如常数、一次函数
            rel < 0.01 -> 6.0     // 平缓，如 x^2
            rel < 0.05 -> 3.0     // 中等，如 sin
            rel < 0.2 -> 1.5      // 较剧烈
            else -> 0.8           // 剧烈，如 tan / 高频
        }

        val v = (base * factor).coerceIn(10.0, 1000.0)
        return v.toFloat()
    }

    /** 重新绘制 */
    fun plot() {
        error = ""
        try {
            when (mode) {
                GraphMode.CART, GraphMode.POLAR -> {
                    val info = CalcEngine.detectVar(input)
                    varName = info.primary

                    // 保留参数值（重新解析时不要丢）
                    val oldVals = paramValues
                    paramNames = info.params
                    paramValues = info.params.associateWith { oldVals[it] ?: 1f }

                    // 编译 AST（带参求值）
                    val a0 = CalcEngine.compileAst(input)
                    ast = a0
                    astY = null

                    // 自适应：自变量遍历，参数固定为当前值
                    val f: (Double) -> Double = { xv ->
                        CalcEngine.evalMap(a0) { name ->
                            if (name == info.primary) xv
                            else (paramValues[name] ?: 1f).toDouble()
                        }
                    }
                    fn1 = f
                    fn2 = null

                    val (x0, x1) = parseRange(xRange, -10.0, 10.0)
                    val (y0, y1) = parseRange(yRange, -10.0, 10.0)
                    if (x1 <= x0 || y1 <= y0) {
                        error = "范围必须满足 min < max"
                        return
                    }
                    viewport = CalcPlot.Viewport(x0, x1, y0, y1)

                    sliderMax = computeSliderMax(f, x0, x1)
                    if (sliderValue > sliderMax) sliderValue = sliderMax
                }
                GraphMode.PARAM -> {
                    varName = "t"
                    paramNames = emptyList()
                    paramValues = emptyMap()

                    val ax = CalcEngine.compileAst(inputX)
                    val ay = CalcEngine.compileAst(inputY)
                    ast = ax
                    astY = ay
                    fn1 = { t -> CalcEngine.eval(ax, t) }
                    fn2 = { t -> CalcEngine.eval(ay, t) }

                    val (t0, t1) = parseRange(tRange, 0.0, Math.PI * 2)
                    if (t1 <= t0) {
                        error = "t 范围必须 min < max"
                        return
                    }
                    val (x0, x1) = parseRange(xRange, -12.0, 12.0)
                    val (y0, y1) = parseRange(yRange, -12.0, 12.0)
                    viewport = CalcPlot.Viewport(x0, x1, y0, y1)
                    sliderMax = 200f
                }
            }
        } catch (e: Throwable) {
            error = e.message ?: "解析失败"
            fn1 = null
            fn2 = null
        }
    }

    LaunchedEffect(Unit) { plot() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // ---- 类型切换 ----
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            GraphMode.entries.forEach { m ->
                val on = mode == m
                Box(
                    Modifier
                        .background(
                            if (on) pal.accent else pal.paperSoft,
                            RoundedCornerShape(13.dp)
                        )
                        .clip(RoundedCornerShape(13.dp))
                        .pointerInput(m) {
                            detectTapGestures {
                                mode = m
                                input = when (m) {
                                    GraphMode.CART -> "sin(x)"
                                    GraphMode.POLAR -> "1 - sin(theta)"
                                    GraphMode.PARAM -> ""
                                }
                                plot()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        m.label,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) Color.White else pal.inkSoft,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- 输入 ----
        if (mode == GraphMode.PARAM) {
            OutlinedTextField(
                value = inputX,
                onValueChange = { inputX = it; plot() },
                label = { Text("x(t)", fontSize = 11.sp) },
                singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = inputY,
                onValueChange = { inputY = it; plot() },
                label = { Text("y(t)", fontSize = 11.sp) },
                singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = tRange,
                onValueChange = { tRange = it; plot() },
                label = { Text("t 范围 (min,max)", fontSize = 11.sp) },
                singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; plot() },
                label = {
                    Text(
                        if (mode == GraphMode.POLAR) "r = f(θ)" else "y = f(x)",
                        fontSize = 11.sp
                    )
                },
                singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(6.dp))

        // ---- 范围 ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = xRange,
                onValueChange = { xRange = it; plot() },
                label = { Text("X 范围", fontSize = 10.sp) },
                singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = yRange,
                onValueChange = { yRange = it; plot() },
                label = { Text("Y 范围", fontSize = 10.sp) },
                singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))

        // ---- 画布 ----
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, pal.line, RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        var vp = viewport
                        if (zoom != 1f) {
                            vp = vp.zoomAt(
                                cxPx = size.width / 2f,
                                cyPx = size.height / 2f,
                                factor = 1f / zoom,
                                w = size.width.toFloat(),
                                h = size.height.toFloat(),
                            )
                        }
                        if (pan.x != 0f || pan.y != 0f) {
                            vp = vp.pan(
                                dxPx = pan.x,
                                dyPx = pan.y,
                                w = size.width.toFloat(),
                                h = size.height.toFloat(),
                            )
                        }
                        viewport = vp
                    }
                }
        ) {
            // key(paramValues)：参数滑块变动时强制重绘。
            // 否则 fn1 引用没变，Canvas 不会重新执行。
            androidx.compose.runtime.key(paramValues) {
            Canvas(Modifier.fillMaxSize()) {
                val vp = viewport
                CalcPlot.drawAxes(
                    scope = this,
                    v = vp,
                    gridColor = pal.line,
                    axisColor = pal.inkSoft,
                    labelColor = pal.inkFaint,
                    bgColor = Color(0xFFFDFCF9),
                    labelTextSizePx = 10.dp.toPx(),
                )

                val f1 = fn1
                val f2 = fn2
                if (f1 != null) {
                    when (mode) {
                        GraphMode.CART -> CalcPlot.drawFunction(this, vp, f1, pal.accent)
                        GraphMode.POLAR -> CalcPlot.drawPolar(this, vp, f1, Color(0xFF17803D))
                        GraphMode.PARAM -> {
                            if (f2 != null) {
                                val (t0, t1) = parseRange(tRange, 0.0, Math.PI * 2)
                                CalcPlot.drawParam(this, vp, f1, f2, t0, t1, pal.accent)
                            }
                        }
                    }
                }
            }
            }  // key(paramValues)
        }

        Spacer(Modifier.height(10.dp))

        // ---- 滑块：调整绘制采样密度 ----
        //
        // 这不是普通的“调参”滑块，而是控制【绘制精细度】。
        // 上限由函数形态自动算出（见 computeSliderMax），
        // 不会出现“拉到 100 还是很稀疏”或“一拉就爆”的情况。
        Column(
            Modifier
                .fillMaxWidth()
                .background(pal.paperSoft, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "绘制精细度",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = pal.ink,
                )
                Text(
                    "${sliderValue.roundToInt()}  /  ${sliderMax.roundToInt()}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = pal.inkSoft,
                )
            }
            Spacer(Modifier.height(4.dp))
            // 液态玻璃滑块（参考 AndroidLiquidGlass-kmp 的 LiquidSlider，
            // 用本项目 miuix-blur 重实现，不引 kyant 库）
            LiquidGlassSlider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                valueRange = 10f..sliderMax,
                accent = pal.accent,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
            Text(
                "自变量 ${if (varName == "theta") "θ" else varName} · " +
                    "上限按函数形态自动适配（越平缓上限越大）",
                fontSize = 10.sp,
                color = pal.inkFaint,
                lineHeight = 14.sp,
            )
        }

        // ---- 参数滑块 ----
        //
        // 表达式里除自变量以外的变量（a、b、k …）会自动出现在这里。
        // 例如 y = sin(a*pi*x)：拖动 a 就能看到波形被压缩/拉宽。
        if (paramNames.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(pal.paperSoft, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    "参数（自动识别）",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = pal.ink,
                )
                Spacer(Modifier.height(6.dp))

                paramNames.forEach { name ->
                    val cur = paramValues[name] ?: 1f
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (name == "theta") "θ" else name,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = pal.accent,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            CalcFormat.fixed(cur.toDouble(), 2),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = pal.inkSoft,
                        )
                    }
                    Slider(
                        value = cur,
                        onValueChange = {
                            // 只更新值，不重新解析表达式。
                            //
                            // 原因：plot() 内部会把 paramNames/paramValues 重置，
                            // 拖动时反复调会打断滑块；而且 AST 没变，
                            // 只需让重绘读到新值即可（lambda 读的是最新 state）。
                            paramValues = paramValues + (name to it)
                        },
                        valueRange = -10f..10f,
                        colors = SliderDefaults.colors(
                            thumbColor = pal.accent,
                            activeTrackColor = pal.accent,
                            inactiveTrackColor = pal.line,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CalcButton("重置视图", primary = false) {
                xRange = "-10, 10"
                yRange = "-10, 10"
                viewport = CalcPlot.Viewport(-10.0, 10.0, -10.0, 10.0)
                plot()
            }
            CalcButton("重新绘制") { plot() }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            if (error.isNotBlank()) "⚠ " + error
            else "单指拖动平移 · 双指缩放 · 中文符号自动转换 · 三角函数用角度制",
            fontSize = 11.sp,
            color = if (error.isNotBlank()) pal.inkSoft else pal.inkFaint,
            lineHeight = 16.sp,
        )

        Spacer(Modifier.height(28.dp))
    }
}
