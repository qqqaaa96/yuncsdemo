package com.csdemo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.ui.theme.LocalPalette

/**
 * 页面标题栏：左边一个绘制的返回箭头，右边是标题。
 *
 * 视觉：
 *   ‹ 标题
 *
 * 用处：只给「常用功能」与「工具」里的子页面用。
 */
@Composable
fun PageHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pal = LocalPalette.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackArrow(onClick = onBack, tint = pal.ink)
        Spacer(Modifier.width(2.dp))
        Text(
            text = title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = pal.ink,
        )
    }
}

/**
 * 绘制的左向返回箭头（内部使用）。
 */
@Composable
fun BackArrow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val s = this.size.minDimension
            val stroke = Stroke(
                width = s * 0.14f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            val midY = s / 2f
            val leftX = s * 0.20f
            val rightX = s * 0.80f
            val dy = s * 0.30f
            val path = Path().apply {
                moveTo(rightX, midY - dy)
                lineTo(leftX, midY)
                lineTo(rightX, midY + dy)
            }
            drawPath(path, color = tint, style = stroke)
        }
    }
}
