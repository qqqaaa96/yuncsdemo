package com.csdemo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.csdemo.ui.theme.LocalPalette
import com.csdemo.ui.theme.Ink

/**
 * 页面返回键：绘制的箭头。
 *
 * 完全用 Canvas 画出来（不用文字 “←”、不用图标库），
 * 统一线宽与圆角，视觉与项目风格一致。
 *
 * @param onClick 点击回调
 * @param tint    箭头颜色，默认取当前主题的文字色
 */
@Composable
fun BackArrow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    size: Dp = 40.dp,
    arrowSize: Dp = 20.dp,
) {
    val pal = LocalPalette.current
    val color = tint ?: pal.ink

    Box(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(arrowSize)) {
            val s = this.size.minDimension
            val stroke = Stroke(
                width = s * 0.13f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            // 左向箭头：一条从右上到左中的斜线 + 一条从左中到右下的斜线
            val midY = s / 2f
            val leftX = s * 0.18f
            val rightX = s * 0.82f
            val dy = s * 0.32f

            val path = Path().apply {
                moveTo(rightX, midY - dy)
                lineTo(leftX, midY)
                lineTo(rightX, midY + dy)
            }
            drawPath(path, color = color, style = stroke)
        }
    }
}
