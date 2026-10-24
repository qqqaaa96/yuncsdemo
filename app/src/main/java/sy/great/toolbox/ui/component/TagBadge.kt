package sy.great.toolbox.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.tools.SyncCenter
import sy.great.toolbox.ui.theme.LocalPalette

/**
 * 称号标签。
 *
 * 两种渲染模式：
 *   1. 普通：单色背景胶囊
 *   2. 炫彩流光：渐变文字 + 背景流动（颜色循环）
 *
 * 流光实现：
 *   用 Compose 的 infiniteTransition 循环 0..1，
 *   映射成渐变的 start/end 偏移，形成色块从左向右流动的视觉效果。
 *   不需要 Shader，纯 Brush.linearGradient 即可，性能开销很低。
 *
 * 颜色数量 2~5 个（后端限制），自动首尾相接保证循环无缝。
 */
@Composable
fun TagBadge(
    tag: SyncCenter.Tag,
    modifier: Modifier = Modifier,
    fontSize: Int = 11,
    filled: Boolean = true,
) {
    if (tag.text.isBlank()) return

    val pal = LocalPalette.current

    // 内边距按字号缩放：11sp 用 7/2dp，8sp 用 5/1dp
    val padH = (fontSize * 0.62f).dp
    val padV = (fontSize * 0.18f).dp
    val pillRadius = (fontSize * 0.55f).dp

    // ---- 炫彩流光模式 ----
    if (tag.hasFx) {
        val colors = tag.fxColors.mapNotNull { parseHex(it) }
        if (colors.size >= 2) {
            // 首尾相接，让流动无缝
            val looped = colors + colors.first()

            val transition = rememberInfiniteTransition(label = "tag-fx")
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2600, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "tag-fx-phase",
            )

            // 固定参考宽度，不做实时测量（与 FxText 同理，避免重组循环）
            val refW = 160f

            Box(
                modifier
                    .clip(RoundedCornerShape(pillRadius))
                    .background(
                        // 背景用同一个平移量，才能和文字同步流动
                        Brush.horizontalGradient(
                            colors = looped.map { it.copy(alpha = 0.16f) } + looped.first().copy(alpha = 0.16f),
                            startX = phase * refW * 2f,
                            endX = phase * refW * 2f + refW * 2f,
                        )
                    )
                    .padding(horizontal = maxOf(padH, 3.dp), vertical = maxOf(padV, 1.dp)),
            ) {
                Text(
                    text = tag.text,
                    fontSize = fontSize.sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(
                        brush = Brush.horizontalGradient(
                            colors = looped,
                            startX = phase * refW * 2f,
                            endX = phase * refW * 2f + refW * 2f,
                        )
                    ),
                )
            }
            return
        }
    }

    // ---- 普通模式 ----
    val base = parseHex(tag.color) ?: pal.accent

    Box(
        modifier
            .clip(RoundedCornerShape(pillRadius))
            .background(if (filled) base else base.copy(alpha = 0.12f))
            .padding(horizontal = maxOf(padH, 3.dp), vertical = maxOf(padV, 1.dp)),
    ) {
        Text(
            text = tag.text,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Medium,
            color = if (filled) Color.White else base,
        )
    }
}

/**
 * 带流光效果的单行文字（不带背景胶囊）。
 *
 * 用于「名字」这类直接展示的文本：
 *   · fxColors >= 2 → 渐变文字流动
 *   · 否则 → 单色（color 为空时用主题色）
 *
 * 与 TagBadge 的区别：TagBadge 有背景胶囊，FxText 纯文字。
 */
@Composable
fun FxText(
    text: String,
    color: String,
    fxColors: List<String>,
    modifier: Modifier = Modifier,
    fontSize: Int = 15,
    fontWeight: FontWeight = FontWeight.SemiBold,
    maxLines: Int = 1,
) {
    val pal = LocalPalette.current

    // ---- 流光模式 ----
    val colors = fxColors.mapNotNull { parseHex(it) }
    if (colors.size >= 2) {
        val looped = colors + colors.first()

        val transition = rememberInfiniteTransition(label = "fxt")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "fxt-phase",
        )

        // 渐变位移量：不依赖实时测量宽度。
        //
        // 之前用 onSizeChanged 写 state 再读它拼 Brush，
        // 导致每次宽度变化都要重组一次，而且列表里 N 个 FxText
        // 就是 N 个独立无限动画，开销很大。
        //
        // 改成固定参考宽度（200px），视觉上流速一致且零测量开销。
        val refW = 200f

        Text(
            text = text,
            modifier = modifier,
            fontSize = fontSize.sp,
            fontWeight = fontWeight,
            maxLines = maxLines,
            style = TextStyle(
                brush = Brush.horizontalGradient(
                    colors = looped,
                    startX = phase * refW * 2f,
                    endX = phase * refW * 2f + refW * 2f,
                )
            ),
        )
        return
    }

    // ---- 单色 ----
    val base = parseHex(color) ?: pal.ink
    Text(
        text = text,
        modifier = modifier,
        fontSize = fontSize.sp,
        fontWeight = fontWeight,
        color = base,
        maxLines = maxLines,
    )
}

/** #RRGGBB → Color，失败返回 null */
private fun parseHex(hex: String): Color? {
    return try {
        if (hex.length == 7 && hex[0] == '#') {
            Color(android.graphics.Color.parseColor(hex))
        } else null
    } catch (_: Throwable) {
        null
    }
}
