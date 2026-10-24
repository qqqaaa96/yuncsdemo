package sy.great.toolbox.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.tools.SyncCenter
import sy.great.toolbox.ui.pressable
import sy.great.toolbox.ui.theme.LocalPalette
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text

/**
 * 主页签到卡。
 *
 * 展示：
 *   · 当前积分
 *   · 连续签到天数
 *   · 今日签到按钮（已签则置灰）
 *
 * 无数据时（未登录 / 未同步）展示占位而不是隐藏，
 * 避免主页布局因为登录状态而跳动。
 */
@Composable
fun CheckinCard(
    stat: SyncCenter.Stat?,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onCheckin: () -> Unit,
) {
    val pal = LocalPalette.current
    val canCheckin = stat?.canCheckin == true && !busy

    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ---- 积分 ----
                Column(Modifier.weight(1f)) {
                    Text(
                        "积分",
                        fontSize = 11.sp,
                        color = pal.inkSoft,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        (stat?.points ?: 0).toString(),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = pal.ink,
                    )
                }

                // ---- 连续签到 ----
                Column(Modifier.weight(1f)) {
                    Text(
                        "连续签到",
                        fontSize = 11.sp,
                        color = pal.inkSoft,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            (stat?.streak ?: 0).toString(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = pal.ink,
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            "天",
                            fontSize = 12.sp,
                            color = pal.inkSoft,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }

                // ---- 签到按钮 ----
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (canCheckin) pal.accent
                            else pal.paperSoft
                        )
                        .pressable(enabled = canCheckin, pressedScale = 0.95f, onClick = onCheckin)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            busy -> "签到中..."
                            stat == null -> "签到"
                            stat.canCheckin -> "签到"
                            else -> "已签到"
                        },
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (canCheckin) androidx.compose.ui.graphics.Color.White else pal.inkSoft,
                    )
                }
            }

            // ---- 等级 + 经验条 ----
            if (stat != null) {
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 等级徽章
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(pal.accent.copy(alpha = 0.12f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "Lv.${stat.level}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = pal.accent,
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    // 经验进度条
                    val span = (stat.expNext - stat.expBase).coerceAtLeast(1)
                    val done = (stat.exp - stat.expBase).coerceIn(0, span)
                    val pct = done.toFloat() / span

                    Box(
                        Modifier
                            .weight(1f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(pal.line.copy(alpha = 0.5f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(pct)
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(pal.accent),
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    Text(
                        "${stat.exp}/${stat.expNext}",
                        fontSize = 10.5.sp,
                        color = pal.inkSoft,
                    )
                }

                // 累计 + 连续加成
                if (stat.total > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "累计签到 ${stat.total} 天" +
                                if (stat.streak > 0) "　·　连续 ${stat.streak} 天加成 +${minOf(50, maxOf(0, stat.streak - 1) * 2)}%"
                                else "",
                        fontSize = 11.5.sp,
                        color = pal.inkSoft,
                    )
                }
            }
        }
    }
}
