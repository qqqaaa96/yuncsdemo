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
 * 【来源】
 *   交互与视觉参考 AndroidLiquidGlass-kmp 的 LiquidSlider：
 *     · 轨道：细胶囊，已填充部分高亮色
 *     · 拖块：胶囊形，按下时放大到 1.5 倍，带折射/模糊
 *     · 点击轨道任意位置 → 平滑动画到位
 *     · 拖动时按速度做轻微拉长/压扁
 *
 * 【实现差异（重要）】
 *   原版用 com.kyant.backdrop 库（KMP 产物）。
 *   本项目不能引那个库——它在 AndroidX Compose 下运行必崩
 *   （见交接文档 3.10：GraphicsLayer/Modifier.Node 二进制不兼容）。
 *
 *   所以这里用项目自带的 miuix-blur 重写：
 *     · drawBackdrop + lens() 负责折射（与邮件卡片同一套）
 *     · vibrancy() 负责色彩提升
 *     · 动画用 Animatable + spring，参数照搬原版
 *
 * 说明：
 *   原版拖块按下时是“从白变透明，逐渐露出玻璃”。
 *   这里用 alpha 控制白色遮罩层，效果一致。
 */
@Composable
fun LiquidGlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF1B6EF3),
    trackColor: Color = Color(0xFF787878).copy(alpha = 0.2f),
) {
    val scope = rememberCoroutineScope()

    // 轨道自身的 backdrop 采样层（拖动块从它取图）
    val trackBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackWidthPx = constraints.maxWidth.toFloat()

        // 归一化进度 0..1
        val span = (valueRange.endInclusive - valueRange.start).let { if (it <= 0f) 1f else it }
        val progress = ((value - valueRange.start) / span).coerceIn(0f, 1f)

        // 拖动累积值。
        //
        // 为什么需要它：
        //   pointerInput 的 lambda 会捕获当帧的 `value`，
        //   拖动过程中 value 变了下一次手势回调读到的还是旧值，
        //   结果就是“拖了没反应”。
        //   用独立的可变状态记累积值，每次拖动只做增量。
        var dragAccum by remember { mutableStateOf(value) }

        // 外部值变化时同步（比如重置按钮、参数滑块联动）
        androidx.compose.runtime.LaunchedEffect(value) {
            dragAccum = value
        }

        // 按压动画：0..1
        val pressAnim = remember { Animatable(0f) }
        // 缩放：按下放大
        val scaleAnim = remember { Animatable(1f) }

        // 拖块宽度 40dp、高度 24dp（与原版一致）
        val thumbW = 40.dp
        val thumbH = 24.dp

        // 位移量（像素），把拖块中心对准进度位置
        val thumbWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { thumbW.toPx() }
        val maxOffsetPx = trackWidthPx - thumbWidthPx

        // ---- 轨道 ----
        Box(Modifier.layerBackdrop(trackBackdrop)) {
            // 底色轨
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(trackColor)
                    // 点击轨道跳转
                    .pointerInput(valueRange) {
                        detectTapGestures { pos ->
                            val p = (pos.x / trackWidthPx).coerceIn(0f, 1f)
                            val target = valueRange.start + span * p
                            onValueChange(target)
                        }
                    }
            )
            // 已填充部分
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
                    // 位置：进度 × 可移动范围
                    translationX = maxOffsetPx * progress
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
                            if (maxOffsetPx <= 0f) return@detectHorizontalDragGestures
                            // 像素位移 → 值变化（用累积值，避免读到旧闭包值）
                            val delta = span * (dragAmount / maxOffsetPx)
                            val nv = (dragAccum + delta).coerceIn(valueRange)
                            dragAccum = nv
                            onValueChange(nv)
                        },
                    )
                }
                // 液态玻璃外观
                .drawBackdrop(
                    backdrop = trackBackdrop,
                    shape = { RoundedCornerShape(12.dp) },
                    effects = {
                        vibrancy()
                        // 按下时逐渐出现折射（与邮件卡片同一套 lens）
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
                        // 未按下时是白色胶囊；按下后白色淡出，露出下面的玻璃
                        val p = pressAnim.value
                        drawRect(Color.White.copy(alpha = 1f - p))
                    },
                )
        )
    }
}
