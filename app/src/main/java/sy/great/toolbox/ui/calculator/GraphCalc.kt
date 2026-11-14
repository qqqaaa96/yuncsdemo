package sy.great.toolbox.ui.calculator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.tools.CalcEngine
import sy.great.toolbox.tools.CalcFormat
import sy.great.toolbox.tools.CalcPlot
import sy.great.toolbox.ui.theme.LocalPalette
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 函数图像计算器（纯 Kotlin）。
 *
 * 使用方式只有一件事：
 *   在输入框里写式子 → 自动出图。
 *
 * 不需要选模式，自动识别：
 *   y = sin(x)              → 显函数
 *   x^2 + y^2 = 25          → 隐函数（圆）
 *   r = 1 - sin(theta)      → 极坐标
 *   sin(x)                  → 显函数（默认）
 *
 * 符号自动转换：
 *   中文括号、全角字符、× ÷ −、上标²³、θ π √
 *   全部自动处理，用户不用管。
 *
 * 参数自动识别：
 *   式子里除 x/y/theta 外的字母（a、b、k…）
 *   会自动生成滑块，拖动实时重绘。
 */
@Composable
fun GraphCalc() {
    val pal = LocalPalette.current

    // 输入内容
    var input by remember {
        mutableStateOf("y = (x^2)^(1/3) + 0.9*(8 - x^2)^(1/2)*sin(a*pi*x)")
    }

    // 错误信息
    var error by remember { mutableStateOf("") }

    // 视窗
    var viewport by remember { mutableStateOf(CalcPlot.Viewport(-3.0, 3.0, -2.0, 3.0)) }

    // 当前绘制方式
    var drawKind by remember { mutableStateOf(CalcEngine.Kind.EXPLICIT) }

    // 【关键】只存 AST，不存闭包。
    //
    // 之前存的是 `(Double) -> Double` 闭包，闭包内读的 paramValues
    // 是创建那一刻的快照 -> 拖滑块图像不会变。
    // 改成存 AST，在 Canvas 绘制时现拼函数，每次都能读到最新参数值。
    var astY by remember { mutableStateOf<CalcEngine.Node?>(null) }
    var astXY by remember { mutableStateOf<CalcEngine.Node?>(null) }

    // 参数与其值
    var paramNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var paramValues by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }

    // ---- 解析 ----
    fun parse() {
        error = ""
        try {
            val kind = CalcEngine.classify(input)
            drawKind = kind
            astXY = null
            astY = null

            // 自动识别参数
            val info = CalcEngine.detectVar(input)
            val old = paramValues
            paramNames = info.params
            paramValues = info.params.associateWith { old[it] ?: 1f }

            when (kind) {
                CalcEngine.Kind.IMPLICIT -> {
                    astXY = CalcEngine.compileAst(CalcEngine.implicitExpr(input))
                    viewport = CalcPlot.Viewport(-3.0, 3.0, -3.0, 3.0)
                }
                CalcEngine.Kind.POLAR -> {
                    astY = CalcEngine.compileAst(input)
                    viewport = CalcPlot.Viewport(-6.0, 6.0, -6.0, 6.0)
                }
                CalcEngine.Kind.EXPLICIT -> {
                    val ast = CalcEngine.compileAst(input)
                    astY = ast

                    // 自动缩纵轴（照搬 HTML：扫 400 点看值域）
                    var lo = Double.POSITIVE_INFINITY
                    var hi = Double.NEGATIVE_INFINITY
                    for (i in 0..400) {
                        val x = -10.0 + 20.0 * i / 400
                        val y = try {
                            CalcEngine.evalMap(ast) { name ->
                                when (name) {
                                    "x" -> x
                                    else -> (paramValues[name] ?: 1f).toDouble()
                                }
                            }
                        } catch (_: Throwable) { Double.NaN }
                        if (!y.isFinite() || abs(y) > 1e8) continue
                        if (y < lo) lo = y
                        if (y > hi) hi = y
                    }
                    if (lo.isFinite() && hi.isFinite() && hi - lo < 300) {
                        val pad = if (hi - lo == 0.0) 1.0 else (hi - lo) * 0.15
                        viewport = CalcPlot.Viewport(-10.0, 10.0, lo - pad, hi + pad)
                    } else {
                        viewport = CalcPlot.Viewport(-10.0, 10.0, -10.0, 10.0)
                    }
                }
            }
        } catch (e: Throwable) {
            error = e.message ?: "解析失败"
            astY = null
            astXY = null
        }
    }

    // 首次解析
    androidx.compose.runtime.LaunchedEffect(Unit) { parse() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // ---- 输入框（只有一个）----
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; parse() },
            placeholder = {
                Text("输入式子，如 y=sin(x) 或 x^2+y^2=25", fontSize = 12.sp)
            },
            singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )

        if (error.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("⚠ $error", fontSize = 11.sp, color = pal.inkSoft)
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
            // key(paramValues)：参数一变就重建 Canvas，
            // 让下面的 lambda 重新执行并读到新的 paramValues。
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

                    // 【关键】每次绘制都现拼函数，参数值从 paramValues 实时取。
                    // 不能缓成闭包，否则拖滑块时图像不刷新。
                    val vals = paramValues
                    val a1 = astY
                    val a2 = astXY

                    when {
                        a2 != null -> {
                            val f: (Double, Double) -> Double = { x, y ->
                                CalcEngine.evalMap(a2) { name ->
                                    when (name) {
                                        "x" -> x
                                        "y" -> y
                                        else -> (vals[name] ?: 1f).toDouble()
                                    }
                                }
                            }
                            CalcPlot.drawImplicit(this, vp, f, pal.accent)
                        }
                        a1 != null && drawKind == CalcEngine.Kind.POLAR -> {
                            val f: (Double) -> Double = { th ->
                                CalcEngine.evalMap(a1) { name ->
                                    when (name) {
                                        "theta" -> th
                                        else -> (vals[name] ?: 1f).toDouble()
                                    }
                                }
                            }
                            CalcPlot.drawPolar(this, vp, f, Color(0xFF17803D))
                        }
                        a1 != null -> {
                            val f: (Double) -> Double = { xv ->
                                CalcEngine.evalMap(a1) { name ->
                                    when (name) {
                                        "x" -> xv
                                        else -> (vals[name] ?: 1f).toDouble()
                                    }
                                }
                            }
                            CalcPlot.drawFunction(this, vp, f, pal.accent)
                        }
                    }
                }
            }
        }

        // ---- 参数滑块（自动出现）----
        if (paramNames.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(pal.paperSoft, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                paramNames.forEach { name ->
                    val cur = paramValues[name] ?: 1f
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            name,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = pal.accent,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            CalcFormat.fixed(cur.toDouble(), 2),
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = pal.inkSoft,
                        )
                    }
                    // 液态玻璃滑块
                    LiquidGlassSlider(
                        value = cur,
                        onValueChange = { paramValues = paramValues + (name to it) },
                        valueRange = -10f..10f,
                        accent = pal.accent,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "单指拖动平移 · 双指缩放手势 · 自动识别显函数/隐函数/极坐标",
            fontSize = 10.sp,
            color = pal.inkFaint,
            lineHeight = 14.sp,
        )
        Spacer(Modifier.height(24.dp))
    }
}
