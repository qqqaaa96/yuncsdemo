package com.csdemo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import com.csdemo.ui.theme.Accent
import com.csdemo.ui.theme.LocalPalette

/**
 * 页面标题栏：左边一个返回箭头，右边是标题。
 *
 * 视觉：
 *   〈 标题
 *
 * 返回箭头用主题蓝（Accent），不用正文黑：
 * 蓝色是全局可点击元素的统一色（胶囊按钮、选中 tab 均同色），
 * 一眼就能看出“这里可以点”，且与白底黑字的正文自然区分开，不突兀。
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
        BackArrow(onClick = onBack, tint = Accent)
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
 * 左向返回箭头（）
 *
 * 形状：只保留尖角与两条斜边，不画横线。
 * 带横线的 “←” 看起来像“上一首/返回历史”，
 * 而 “〈” 才是 iOS / 微信式的“返回上一级”。
 *
 * 点击区 44dp（Android 最小触控尺寸），箭头图形本身不变大，
 * 只把可点击范围撑开，手指更好点中。
 */
@Composable
fun BackArrow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color,
) {
    // 点击区 44dp（Android 最小触控尺寸），但图形保持原大小且靠左对齐：
    // 若居中，44dp 比原来的 36dp 宽 8dp，会把后面的标题整体右推；
    // 靠左对齐则图形位置与原来基本一致，标题不会移。
    Box(
        modifier = modifier
            .size(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        // 图形保持 22dp，右侧偏移 7dp。
        //
        // 7dp 的来源：旧版是 36dp 的 Box + 居中对齐，
        // 图形左边距 = (36 - 22) / 2 = 7dp。
        // 现在 Box 放大到 44dp，若仍居中，左边距会变成 11dp、
        // 图形整体右移且标题被右推。
        //
        // 这里用 offset 而不是 padding：
        // padding 会改变 Canvas 的绘制区尺寸，导致 size / minDimension 变小，
        // 箭头会被压扁或位置偏。offset 只平移，不改尺寸。
        Canvas(Modifier.size(22.dp).offset(x = 7.dp)) {
            val s = this.size.minDimension
            val stroke = Stroke(
                width = s * 0.15f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            val midY = s / 2f
            // 微信／iOS 式返回：上下两条斜边组成尖角。
            //
            // 角度：左边尖角到右端的水平距离与垂直距离大致相等，
            // 得到接近 45° 的“〈”，不会太尖也不会太开。
            // 因为去掉了横线，水平跨度可以比 “←” 小一点，
            // 否则箭头会显得偏大、偏左，与标题间距失衡。
            val tipX = s * 0.22f
            val endX = s * 0.74f
            val dy = (endX - tipX) * 0.92f
            val path = Path().apply {
                moveTo(endX, midY - dy)
                lineTo(tipX, midY)
                lineTo(endX, midY + dy)
            }
            drawPath(path, color = tint, style = stroke)
        }
    }
}
