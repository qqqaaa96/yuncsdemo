package com.csdemo.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.csdemo.ui.theme.Accent
import com.csdemo.ui.theme.Line

/**
 * 克制、统一的动效参数。
 */
object Motion {
    const val FAST = 120
    const val NORMAL = 220
    const val SLOW = 380
    const val STAGGER = 45
}

/**
 * 可按压效果：按下缩到 0.965，松手回弹。
 */
fun Modifier.pressable(
    pressedScale: Float = 0.965f,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "press-scale"
    )
    this
        .scale(scale)
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                val up = waitForUpOrCancellation()
                pressed = false
                if (up != null) {
                    up.consume()
                    onClick()
                }
            }
        }
}

/**
 * 错峰入场：根据 index 延迟淡入。
 * 只在进入时跑一次，不会因为重组而反复。
 */
fun Modifier.staggerIn(index: Int, animate: Boolean = true): Modifier = composed {
    val alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(
            durationMillis = Motion.SLOW,
            delayMillis = index * Motion.STAGGER,
            easing = FastOutSlowInEasing
        ),
        label = "stagger-alpha"
    )
    this.graphicsLayer {
        this.alpha = if (animate) alpha else 1f
    }
}

/** 旋转加载圈 */
@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    color: Color = Accent
) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinner-angle"
    )
    Canvas(modifier.size(size).graphicsLayer { rotationZ = angle }) {
        val stroke = 2.dp.toPx()
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 280f,
            useCenter = false,
            style = Stroke(width = stroke)
        )
    }
}

/** 呼吸点 */
@Composable
fun Dots(modifier: Modifier = Modifier, color: Color = Accent) {
    val transition = rememberInfiniteTransition(label = "dots")
    val p1 by transition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "d1"
    )
    val p2 by transition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 160), RepeatMode.Reverse), label = "d2"
    )
    val p3 by transition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 320), RepeatMode.Reverse), label = "d3"
    )
    Row(modifier) {
        Dot(p1, color)
        Spacer(Modifier.width(4.dp))
        Dot(p2, color)
        Spacer(Modifier.width(4.dp))
        Dot(p3, color)
    }
}

@Composable
private fun Dot(alpha: Float, color: Color) {
    Box(Modifier.size(5.dp).alpha(alpha).background(color, CircleShape))
}

/**
 * 可横向滑动的行。
 * 内容超出屏幕时可左右滑动，解决竖屏下按钮被挤压看不见的问题。
 */
@Composable
fun ScrollRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        content = content
    )
}

/** 不确定态细进度条 */
@Composable
fun ThinProgress(modifier: Modifier = Modifier, color: Color = Accent) {
    val transition = rememberInfiniteTransition(label = "prog")
    val x by transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "prog-x"
    )
    Canvas(modifier) {
        val h = 2.dp.toPx()
        val w = size.width
        drawRect(color = Line, topLeft = Offset(0f, 0f), size = Size(w, h))
        val barW = w * 0.32f
        drawRect(color = color, topLeft = Offset(w * x, 0f), size = Size(barW, h))
    }
}
