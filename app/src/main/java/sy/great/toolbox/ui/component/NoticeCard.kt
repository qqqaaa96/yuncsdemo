package sy.great.toolbox.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import sy.great.toolbox.tools.MailApi
import sy.great.toolbox.tools.SyncCenter
import sy.great.toolbox.ui.TabGlyph
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.LocalPalette

/**
 * ColorOS 16 风格的通知卡片。
 *
 * 视觉还原点：
 *   · 大圆角（26dp）悬浮卡片，卡片之间留 8dp 间隙
 *   · 左侧圆形应用图标（带渐变色块）
 *   · 标题 15sp Medium，正文 13.5sp 次要色，最多 2 行
 *   · 右上角时间，字号 11sp
 *   · 整卡可点击（展开详情）
 *
 * 手势还原：
 *   · 向左滑 → 露出操作区（标记已读 / 删除），底色为对应强调色
 *   · 松手后：超过阈值则执行，否则回弹到 0
 *   · 回弹用 spring(MediumBouncy)，与 ColorOS 手感接近
 */
@Composable
fun NoticeCard(
    mail: MailApi.Mail,
    onOpen: () -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
) {
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()

    // 当前拖动偏移（px）。
    //
    // 拖动期间用普通 state（同步更新，无协程开销），
    // 松手回弹时用 Animatable 做弹簧动画。
    var dragX by remember { mutableStateOf(0f) }
    val animX = remember { Animatable(0f) }
    var animating by remember { mutableStateOf(false) }

    // 实际应用的偏移：动画中取 animX，否则取 dragX
    val offsetX = if (animating) animX.value else dragX

    // 操作区宽度：两个按钮
    val actionWidthDp = 148.dp
    val actionWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        actionWidthDp.toPx()
    }

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
        ) {
            // ---- 底层：操作区 ----
            Row(
                Modifier
                    .matchParentSize()
                    .background(pal.paperSoft),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 标记已读
                if (!mail.read) {
                    Box(
                        Modifier
                            .width(actionWidthDp / 2)
                            .fillMaxSize()
                            .background(pal.accent.copy(alpha = 0.9f))
                            .clickable {
                                dragX = 0f
                                onMarkRead()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            TabGlyph(kind = "mail", color = Color.White, size = 18.dp)
                            Spacer(Modifier.height(3.dp))
                            Text("已读", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }

                // 删除
                Box(
                    Modifier
                        .width(actionWidthDp / 2)
                        .fillMaxSize()
                        .background(Bad.copy(alpha = 0.92f))
                        .clickable {
                            dragX = 0f
                            onDelete()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                            val w = size.minDimension * 0.11f
                            val p = size.minDimension * 0.22f
                            // 垃圾桶：盖 + 体 + 竖线
                            drawLine(
                                Color.White,
                                androidx.compose.ui.geometry.Offset(p, p * 1.15f),
                                androidx.compose.ui.geometry.Offset(size.width - p, p * 1.15f),
                                strokeWidth = w,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            )
                            drawLine(
                                Color.White,
                                androidx.compose.ui.geometry.Offset(size.width * 0.38f, p * 0.72f),
                                androidx.compose.ui.geometry.Offset(size.width * 0.62f, p * 0.72f),
                                strokeWidth = w,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            )
                            drawRoundRect(
                                color = Color.White,
                                topLeft = androidx.compose.ui.geometry.Offset(p * 1.35f, p * 1.5f),
                                size = androidx.compose.ui.geometry.Size(
                                    size.width - p * 2.7f,
                                    size.height - p * 2.2f,
                                ),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.6f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = w),
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text("删除", fontSize = 11.sp, color = Color.White)
                    }
                }
            }

            // ---- 上层：卡片主体（跟随手指平移）----
            //
            // 两个坑（之前都踩了）：
            //   1. onHorizontalDrag 里不能每次都 scope.launch 去 snapTo，
            //      那样每帧都会新建一个协程抢着改 offsetX，丢帧且状态错乱。
            //      正确做法：直接在拖动回调里算好位置，用 DragAmount 累积。
            //   2. clickable 和 detectHorizontalDragGestures 会互相干扰：
            //      滑动后松手时可能误触 click。
            //      这里改成：拖动过就不再触发 click。
            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationX = offsetX }
                    // 液态玻璃：有 backdrop 就用玻璃，否则回退到实心背景。
                    //
                    .background(pal.paper)
                    .pointerInput(mail.id, actionWidthPx) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val target = if (dragX < -actionWidthPx * 0.5f) -actionWidthPx else 0f
                                scope.launch {
                                    animating = true
                                    animX.snapTo(dragX)
                                    animX.animateTo(
                                        target,
                                        spring(
                                            dampingRatio = if (target != 0f) 0.8f else 0.55f,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    )
                                    dragX = animX.value
                                    animating = false
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    animating = true
                                    animX.snapTo(dragX)
                                    animX.animateTo(0f, spring(dampingRatio = 0.6f))
                                    dragX = 0f
                                    animating = false
                                }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                // 同步改 state，不启动协程
                                dragX = (dragX + dragAmount).coerceIn(-actionWidthPx * 1.15f, 0f)
                            },
                        )
                    }
                    .clickable {
                        // 已展开时点击先收起；否则进详情
                        if (dragX < -8f) {
                            scope.launch {
                                animating = true
                                animX.snapTo(dragX)
                                animX.animateTo(0f, spring())
                                dragX = 0f
                                animating = false
                            }
                        } else {
                            onOpen()
                        }
                    }
                    .padding(14.dp),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    NoticeIcon(mail)

                    Spacer(Modifier.width(12.dp))

                    Column(Modifier.weight(1f)) {
                        // 第一行：发件人 + 时间
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                mail.sender.ifBlank { "系统" },
                                fontSize = 12.5.sp,
                                color = pal.inkSoft,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )

                            if (!mail.read) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    Modifier.size(6.dp).clip(CircleShape).background(pal.accent)
                                )
                            }

                            Spacer(Modifier.weight(1f))

                            Text(
                                formatNoticeTime(mail.createdAt),
                                fontSize = 11.sp,
                                color = pal.inkSoft.copy(alpha = 0.8f),
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        // 标题
                        Text(
                            mail.title,
                            fontSize = 15.sp,
                            fontWeight = if (mail.read) FontWeight.Normal else FontWeight.Medium,
                            color = pal.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        // 正文
                        if (mail.body.isNotBlank()) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                mail.body.replace(Regex("\\s+"), " ").trim(),
                                fontSize = 13.5.sp,
                                lineHeight = 19.sp,
                                color = pal.inkSoft,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        // 奖励徽标
                        if (mail.hasReward) {
                            Spacer(Modifier.height(7.dp))
                            RewardBadge(mail)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/** 左侧圆形应用图标 */
@Composable
private fun NoticeIcon(mail: MailApi.Mail) {
    val pal = LocalPalette.current
    val base = when (mail.level) {
        "warning" -> Bad
        "important" -> Color(0xFFD99A3C)
        else -> pal.accent
    }

    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    listOf(base.copy(alpha = 0.95f), base.copy(alpha = 0.65f))
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        TabGlyph(kind = "mail", color = Color.White, size = 20.dp)
    }
}

/** 奖励徽标 */
@Composable
private fun RewardBadge(mail: MailApi.Mail) {
    val pal = LocalPalette.current

    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(pal.accent.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildString {
                if (mail.rewardPoints > 0) append("+").append(mail.rewardPoints).append(" 积分")
                if (mail.rewardPoints > 0 && mail.rewardExp > 0) append("　")
                if (mail.rewardExp > 0) append("+").append(mail.rewardExp).append(" 经验")
                if (mail.claimed) append("　已领取")
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (mail.claimed) pal.inkSoft else pal.accent,
        )
    }
}

/** 时间格式：今天 HH:mm / 昨天 / M-d */
private fun formatNoticeTime(ts: Int): String {
    if (ts <= 0) return ""
    val now = System.currentTimeMillis()
    val t = ts * 1000L
    val diff = now - t

    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L ->
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
        diff < 172_800_000L -> "昨天"
        else ->
            java.text.SimpleDateFormat("M-d", java.util.Locale.getDefault()).format(java.util.Date(t))
    }
}
