package sy.great.toolbox.ui.calculator

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sy.great.toolbox.ui.liquid.lens
import sy.great.toolbox.ui.liquid.vibrancy
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * 液态玻璃滑块。
 *
 * 交互与视觉参考 AndroidLiquidGlass-kmp 的 LiquidSlider，
 * 但用本项目 miuix-blur 实现（不能引 kyant 库，会运行崩溃）。
 *
 * 关键修复：
 *   拖动时必须用独立的累积状态，
 *   否则 pointerInput 闭包捕获的是旧的 `value`，拖了没反应。
 */
@Composable
fun LiquidGlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF1B6EF3),
    trackColor: Color = Color(0xFF787878).copy(alpha = 0.22f),
) {
    val scope = rememberCoroutineScope()
    val trackBackdrop = rememberLayerBackdrop()

    // 拖动累积值（不能用 value 直接算，闭包会拿到旧值）
    var dragAccum by remember { mutableStateOf(value) }
    LaunchedEffect(value) { dragAccum = value }

    // 按压动画
    val pressAnim = remember { Animatable(0f) }
    val scaleAnim = remember { Animatable(1f) }

    BoxWithConstraints(
        modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackW = constraints.maxWidth.toFloat()
        val span = (valueRange.endInclusive - valueRange.start).let { if (it <= 0f) 1f else it }
        val progress = ((value - valueRange.start) / span).coerceIn(0f, 1f)

        val thumbW = 40.dp
        val thumbH = 24.dp
        val density = LocalDensity.current
        val thumbPx = with(density) { thumbW.toPx() }
        val maxOffset = (trackW - thumbPx).coerceAtLeast(1f)

        // ---- 轨道 ----
        Box(Modifier.layerBackdrop(trackBackdrop)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(trackColor)
                    .pointerInput(valueRange) {
                        detectTapGestures { pos ->
                            if (trackW <= 0f) return@detectTapGestures
                            val p = (pos.x / trackW).coerceIn(0f, 1f)
                            onValueChange(valueRange.start + span * p)
                        }
                    }
            )
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(accent)
            )
        }

        // ---- 拖块 ----
        Box(
            Modifier
                .graphicsLayer {
                    translationX = maxOffset * progress
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                }
                .size(thumbW, thumbH)
                .pointerInput(valueRange) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            scope.launch {
                                pressAnim.animateTo(1f, spring(dampingRatio = 1f, stiffness = 1000f))
                                scaleAnim.animateTo(1.5f, spring(dampingRatio = 1f, stiffness = 1000f))
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                pressAnim.animateTo(0f, spring(dampingRatio = 1f, stiffness = 1000f))
                                scaleAnim.animateTo(1f, spring(dampingRatio = 1f, stiffness = 1000f))
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                pressAnim.animateTo(0f, spring(dampingRatio = 1f, stiffness = 1000f))
                                scaleAnim.animateTo(1f, spring(dampingRatio = 1f, stiffness = 1000f))
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            val delta = span * (dragAmount / maxOffset)
                            val nv = (dragAccum + delta).coerceIn(valueRange)
                            dragAccum = nv
                            onValueChange(nv)
                        },
                    )
                }
                .drawBackdrop(
                    backdrop = trackBackdrop,
                    shape = { RoundedCornerShape(12.dp) },
                    effects = {
                        vibrancy()
                        val p = pressAnim.value
                        if (p > 0.01f) {
                            blur(6.dp.toPx() * (1f - p))
                            lens(
                                refractionHeight = 10.dp.toPx() * p,
                                refractionAmount = 14.dp.toPx() * p,
                                chromaticAberration = 0.15f * p,
                            )
                        }
                    },
                    onDrawSurface = {
                        val p = pressAnim.value
                        drawRect(Color.White.copy(alpha = 1f - p))
                    },
                )
        )
    }
}
