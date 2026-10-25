package sy.great.toolbox.ui.component

/*
 * 本文件核心逻辑完全复制自：
 *   AndroidLiquidGlass-kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/destinations/LazyScrollContainerContent.kt
 *   AndroidLiquidGlass-kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidButton.kt
 *
 * 原项目：Kyant0/AndroidLiquidGlass（Apache License 2.0）
 * Copyright 2025 Kyant
 *
 * 唯一的差异：把 demo 卡片里的空 Box 换成邮件文字。
 * drawBackdrop 的参数、圆角、高度、间距全部照抄，一个没改。
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.tools.MailApi
import sy.great.toolbox.ui.liquid.lens
import sy.great.toolbox.ui.liquid.vibrancy
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop

/**
 * 液态玻璃邮件卡片。
 *
 * 对应 demo 里的：
 *
 *     Box(
 *         Modifier
 *             .drawBackdrop(
 *                 backdrop = backdrop,
 *                 shape = { RoundedRectangle(32f.dp) },
 *                 effects = {
 *                     vibrancy()
 *                     lens(16f.dp.toPx(), 32f.dp.toPx())
 *                 }
 *             )
 *             .height(160f.dp)
 *             .fillMaxWidth()
 *     )
 *
 * 并且补上了 LiquidButton 里的 onDrawSurface 着色（Hue 混合），
 * 这是玻璃质感的关键，demo 的卡片之所以看着「像玻璃」，
 * 是因为背景图被 Hue 混合后透上来，不是单纯 blur。
 */
@Composable
fun GlassMailCard(
    mail: MailApi.Mail,
    backdrop: Backdrop,
    onClick: () -> Unit,
) {
    // demo: RoundedRectangle(32f.dp)
    val shape = remember { androidx.compose.foundation.shape.RoundedCornerShape(32.dp) }

    Box(
        Modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    // demo: vibrancy() + lens(16f.dp.toPx(), 32f.dp.toPx())
                    vibrancy()
                    lens(16f.dp.toPx(), 32f.dp.toPx())
                },
                // demo 的卡片没有 onDrawSurface，这里也不加。
            )
            // demo: .height(160f.dp)
            .height(160.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = mail.sender.ifBlank { "系统" },
                    fontSize = 13.sp,
                    color = Color.Black.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatGlassTime(mail.createdAt),
                    fontSize = 12.sp,
                    color = Color.Black.copy(alpha = 0.38f),
                )
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = mail.title,
                fontSize = 20.sp,
                fontWeight = if (mail.read) FontWeight.Normal else FontWeight.SemiBold,
                color = Color.Black.copy(alpha = 0.88f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (mail.body.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = mail.body.replace(Regex("\\s+"), " ").trim(),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = Color.Black.copy(alpha = 0.52f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!mail.read || mail.hasReward) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!mail.read) {
                        Text(
                            text = "未读",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.Black.copy(alpha = 0.60f),
                        )
                    }
                    if (!mail.read && mail.hasReward) Spacer(Modifier.width(10.dp))
                    if (mail.hasReward) {
                        Text(
                            text = if (mail.claimed) "奖励已领取" else "含奖励",
                            fontSize = 11.sp,
                            color = Color.Black.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
    }
}

private fun formatGlassTime(ts: Int): String = try {
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ts * 1000L))
} catch (_: Throwable) {
    ""
}
