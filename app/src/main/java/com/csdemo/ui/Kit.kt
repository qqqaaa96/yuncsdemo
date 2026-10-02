package com.csdemo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.ui.theme.Accent
import com.csdemo.ui.theme.Ink
import com.csdemo.ui.theme.InkFaint
import com.csdemo.ui.theme.InkSoft
import com.csdemo.ui.theme.Line
import com.csdemo.ui.theme.Paper
import com.csdemo.ui.theme.PaperSoft

/**
 * 一套统一的 UI 组件，白底黑字。
 * 风格：大圆角卡片、胶囊按钮、圆点刻度滑块、胶囊分段选择。
 */

// ============ 可折叠卡片 ============

@Composable
fun FoldCard(
    title: String,
    initiallyOpen: Boolean = true,
    icon: String? = null,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    var open by remember { mutableStateOf(initiallyOpen) }
    val arrow by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(Motion.NORMAL),
        label = "fold-arrow"
    )

    Column(
        Modifier
            .fillMaxWidth()
            .background(PaperSoft, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(pressedScale = 0.99f) { open = !open },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Text(icon, fontSize = 15.sp, color = InkSoft)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                if (subtitle != null) {
                    Text(subtitle, fontSize = 11.sp, color = InkSoft)
                }
            }
            Text(
                "\u02C4",
                fontSize = 16.sp,
                color = InkSoft,
                modifier = Modifier.graphicsLayer { rotationZ = arrow }
            )
        }

        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(Motion.NORMAL)) + expandVertically(tween(Motion.NORMAL)),
            exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.NORMAL))
        ) {
            Column(Modifier.padding(top = 16.dp)) { content() }
        }
    }
}

// ============ 圆点刻度滑块 ============

/**
 * 带刻度点与竖线手柄的滑块。
 * value 为当前值，range 为可选区间，steps 为刻度数。
 */
@Composable
fun DotSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 20,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Accent
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    var widthPx by remember { mutableStateOf(1f) }

    fun posToValue(x: Float): Float {
        val ratio = (x / widthPx).coerceIn(0f, 1f)
        val raw = range.start + ratio * span
        // 吸附到刻度
        val stepSize = span / (steps - 1).coerceAtLeast(1)
        val snapped = Math.round((raw - range.start) / stepSize) * stepSize + range.start
        return snapped.coerceIn(range.start, range.endInclusive)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(range) {
                detectTapGestures { off -> onValueChange(posToValue(off.x)) }
            }
            .pointerInput(range) {
                detectDragGestures { change, _ ->
                    onValueChange(posToValue(change.position.x))
                    change.consume()
                }
            }
    ) {
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            widthPx = size.width
            val cy = size.height / 2f
            val dotR = 2.5.dp.toPx()
            val ratio = ((value - range.start) / span).coerceIn(0f, 1f)
            val handleX = ratio * size.width

            // 左侧已选区域背景
            drawRoundRect(
                color = accent.copy(alpha = 0.18f),
                topLeft = Offset(0f, cy - 6.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(handleX, 12.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
            )

            // 刻度圆点
            for (i in 0 until steps) {
                val x = if (steps > 1) size.width * i / (steps - 1) else 0f
                val active = x <= handleX
                drawCircle(
                    color = if (active) accent else Line,
                    radius = dotR,
                    center = Offset(x, cy)
                )
            }

            // 竖线手柄
            drawLine(
                color = accent,
                start = Offset(handleX, cy - 11.dp.toPx()),
                end = Offset(handleX, cy + 11.dp.toPx()),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

// ============ 胶囊分段选择 ============

@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Accent
) {
    Row(
        modifier
            .fillMaxWidth()
            .border(1.dp, Line, RoundedCornerShape(50))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .background(
                        if (active) accent.copy(alpha = 0.12f) else Color.Transparent,
                        RoundedCornerShape(50)
                    )
                    .pressable(pressedScale = 0.96f) { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (active) {
                        Text("\u2713", fontSize = 12.sp, color = accent)
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        label,
                        fontSize = 13.sp,
                        color = if (active) accent else InkSoft,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }
    }
}

// ============ 胶囊按钮 ============

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    accent: Color = Accent,
    enabled: Boolean = true,
    leading: String? = null
) {
    Box(
        modifier
            .background(
                if (filled) accent.copy(alpha = if (enabled) 1f else 0.4f)
                else Color.Transparent,
                RoundedCornerShape(50)
            )
            .then(
                if (!filled) Modifier.border(1.dp, Line, RoundedCornerShape(50))
                else Modifier
            )
            .pressable(pressedScale = 0.96f, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Text(leading, fontSize = 12.sp, color = if (filled) Paper else accent)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text,
                fontSize = 13.sp,
                color = if (filled) Paper else accent,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ============ 圆形图标按钮（刷新等） ============

@Composable
fun CircleButton(
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Accent
) {
    Box(
        modifier
            .size(40.dp)
            .background(PaperSoft, CircleShape)
            .pressable(pressedScale = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(icon, fontSize = 16.sp, color = accent)
    }
}

// ============ 列表行 ============

/**
 * 左侧图标 + 主标题 + 标签 + 副文字 + 右侧按钮
 */
@Composable
fun ListRow(
    title: String,
    icon: String? = null,
    tags: List<Pair<String, Color>> = emptyList(),
    subLines: List<String> = emptyList(),
    action: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(pressedScale = 0.99f, onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Text(icon, fontSize = 18.sp, color = InkSoft)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontSize = 15.sp,
                    color = Ink,
                    fontWeight = FontWeight.Medium
                )
                tags.forEach { (t, c) ->
                    Spacer(Modifier.width(6.dp))
                    Tag(t, c)
                }
            }
            subLines.forEach { s ->
                Spacer(Modifier.height(2.dp))
                Text(s, fontSize = 12.sp, color = InkSoft)
            }
        }
        if (action != null) {
            Spacer(Modifier.width(10.dp))
            action()
        }
    }
}

// ============ 数值 + 滑块 组合行 ============

@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 14.sp, color = Ink, modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 14.sp, color = InkSoft)
        }
        Spacer(Modifier.height(4.dp))
        DotSlider(value = value, range = range, steps = steps, onValueChange = onValueChange)
    }
}

// ============ 底部可折叠输出条 ============

@Composable
fun OutputBar(
    title: String = "运行输出",
    content: @Composable () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val arrow by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(Motion.NORMAL),
        label = "out-arrow"
    )
    Column(
        Modifier
            .fillMaxWidth()
            .background(PaperSoft, RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(pressedScale = 0.99f) { open = !open },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("\u25B6", fontSize = 12.sp, color = InkSoft)
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 15.sp, color = Ink, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            Text(
                "\u02C4",
                fontSize = 16.sp,
                color = InkSoft,
                modifier = Modifier.graphicsLayer { rotationZ = arrow }
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(Motion.NORMAL)) + expandVertically(tween(Motion.NORMAL)),
            exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.NORMAL))
        ) {
            Column(Modifier.padding(top = 12.dp)) { content() }
        }
    }
}

// ============ 小标题 ============

@Composable
fun FieldLabel(text: String, help: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 13.sp, color = InkSoft)
        if (help != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(15.dp)
                    .border(1.dp, InkFaint, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("?", fontSize = 9.sp, color = InkFaint)
            }
        }
    }
}
