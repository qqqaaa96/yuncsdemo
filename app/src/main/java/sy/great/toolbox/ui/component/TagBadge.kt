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

            // 让渐变的起止点整体平移
            val span = 3f
            val startX = phase * span
            val endX = startX + span

            val textBrush = Brush.horizontalGradient(
                colors = looped,
                startX = startX * 200f,
                endX = endX * 200f,
            )

            val bgBrush = Brush.horizontalGradient(
                colors = looped.map { it.copy(alpha = 0.14f) }.let {
                    // 首尾也要一致
                    it + it.first()
                },
                startX = startX * 200f,
                endX = endX * 200f,
            )

            Box(
                modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(bgBrush)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    text = tag.text,
                    fontSize = fontSize.sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(brush = textBrush),
                )
            }
            return
        }
    }

    // ---- 普通模式 ----
    val base = parseHex(tag.color) ?: pal.accent

    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled) base else base.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = tag.text,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Medium,
            color = if (filled) Color.White else base,
        )
    }
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
