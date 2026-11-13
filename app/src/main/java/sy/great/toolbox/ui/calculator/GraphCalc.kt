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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.tools.CalcEngine
import sy.great.toolbox.tools.CalcPlot
import sy.great.toolbox.ui.theme.LocalPalette

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
 * 交互：
 *   · 单指拖动平移
 *   · 双指缩放
 *   · 右上角「重置」回到默认视窗
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

    // 当前视窗（拖动/缩放会改它）
    var viewport by remember { mutableStateOf(CalcPlot.Viewport(-10.0, 10.0, -10.0, 10.0)) }

    // 编译结果缓存，避免每帧重解析
    var fn1 by remember { mutableStateOf<((Double) -> Double)?>(null) }
    var fn2 by remember { mutableStateOf<((Double) -> Double)?>(null) }

    fun applyRange() {
        val xs = xRange.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        val ys = yRange.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        val x0 = xs.getOrNull(0) ?: -10.0
        val x1 = xs.getOrNull(1) ?: 10.0
        val y0 = ys.getOrNull(0) ?: -10.0
        val y1 = ys.getOrNull(1) ?: 10.0
        if (x1 <= x0 || y1 <= y0) {
            error = "范围必须满足 min < max"
            return
        }
        viewport = CalcPlot.Viewport(x0, x1, y0, y1)
    }

    fun plot() {
        error = ""
        try {
            when (mode) {
                GraphMode.CART -> {
                    fn1 = CalcEngine.compile(input).second
                    fn2 = null
                    applyRange()
                }
                GraphMode.POLAR -> {
                    fn1 = CalcEngine.compile(input).second
                    fn2 = null
                    applyRange()
                }
                GraphMode.PARAM -> {
                    fn1 = CalcEngine.compile(inputX).second
                    fn2 = CalcEngine.compile(inputY).second
                    val ts = tRange.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                    val t0 = ts.getOrNull(0) ?: 0.0
                    val t1 = ts.getOrNull(1) ?: (Math.PI * 2)
                    if (t1 <= t0) throw IllegalArgumentException("t 范围必须 min < max")
                    applyRange()
                }
            }
        } catch (e: Throwable) {
            error = e.message ?: "解析失败"
            fn1 = null
            fn2 = null
        }
    }

    // 首次自动绘制
    androidx.compose.runtime.LaunchedEffect(Unit) { plot() }

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
                        color = if (on) androidx.compose.ui.graphics.Color.White else pal.inkSoft,
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
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = inputY,
                onValueChange = { inputY = it; plot() },
                label = { Text("y(t)", fontSize = 11.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = tRange,
                onValueChange = { tRange = it; plot() },
                label = { Text("t 范围 (min,max)", fontSize = 11.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; plot() },
                label = { Text(if (mode == GraphMode.POLAR) "r = f(θ)" else "y = f(x)", fontSize = 11.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(6.dp))

        // ---- 范围 ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = xRange,
                onValueChange = { xRange = it; applyRange() },
                label = { Text("X 范围", fontSize = 10.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = yRange,
                onValueChange = { yRange = it; applyRange() },
                label = { Text("Y 范围", fontSize = 10.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
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
            Canvas(Modifier.fillMaxSize()) {
                val vp = viewport

                // 1. 坐标系
                CalcPlot.drawAxes(
                    scope = this,
                    v = vp,
                    gridColor = pal.line,
                    axisColor = pal.inkSoft,
                    labelColor = pal.inkFaint,
                    bgColor = androidx.compose.ui.graphics.Color(0xFFFDFCF9),
                    labelTextSizePx = 10.dp.toPx(),
                )

                // 2. 曲线
                val f1 = fn1
                val f2 = fn2
                if (f1 != null) {
                    when (mode) {
                        GraphMode.CART -> CalcPlot.drawFunction(this, vp, f1, pal.accent)
                        GraphMode.POLAR -> CalcPlot.drawPolar(this, vp, f1, androidx.compose.ui.graphics.Color(0xFF17803D))
                        GraphMode.PARAM -> {
                            if (f2 != null) {
                                val ts = tRange.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                                val t0 = ts.getOrNull(0) ?: 0.0
                                val t1 = ts.getOrNull(1) ?: (Math.PI * 2)
                                CalcPlot.drawParam(this, vp, f1, f2, t0, t1, pal.accent)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CalcButton("重置视图", primary = false) {
                viewport = CalcPlot.Viewport(-10.0, 10.0, -10.0, 10.0)
                xRange = "-10, 10"
                yRange = "-10, 10"
            }
            CalcButton("重新绘制") { plot() }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            if (error.isNotBlank()) "⚠ " + error
            else "单指拖动平移 · 双指缩放 · 三角函数用角度制",
            fontSize = 11.sp,
            color = if (error.isNotBlank()) pal.inkSoft else pal.inkFaint,
            lineHeight = 16.sp,
        )

        Spacer(Modifier.height(28.dp))
    }
}
