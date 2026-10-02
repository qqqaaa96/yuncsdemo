package com.csdemo.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.tools.CardKey
import com.csdemo.tools.Codes
import com.csdemo.tools.Haptics
import com.csdemo.tools.MailAuth
import com.csdemo.tools.SmtpMailer
import com.csdemo.ui.theme.Accent
import com.csdemo.ui.theme.Bad
import com.csdemo.ui.theme.Good
import com.csdemo.ui.theme.Ink
import com.csdemo.ui.theme.InkFaint
import com.csdemo.ui.theme.InkSoft
import com.csdemo.ui.theme.Line
import com.csdemo.ui.theme.Paper
import com.csdemo.ui.theme.PaperSoft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 验证状态 */
private enum class VState { IDLE, CHECKING, OK, FAIL }

/**
 * 卡密验证页（内置数字键盘）。
 *
 * 不使用系统输入法：没有 BasicTextField，键盘完全自绘。
 * 每次冷启动都会进入本页。
 */
@Composable
fun CardKeyScreen(onPassed: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var code by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(VState.IDLE) }
    var errMsg by remember { mutableStateOf("") }

    // 摇动触发器：每次验证结束（无论对错）都递增
    var shakeTick by remember { mutableStateOf(0) }
    // 本次摇动的强度：成功轻、失败重
    var shakeStrength by remember { mutableStateOf(1f) }

    fun submit() {
        if (state == VState.CHECKING || state == VState.OK) return
        if (code.length != CardKey.LEN) return
        state = VState.CHECKING
        errMsg = ""
        scope.launch {
            delay(520)
            val r = MailAuth.check(code)
            if (r.ok) {
                state = VState.OK
                // 成功：轻摇一下，不震动
                shakeStrength = 0.45f
                shakeTick++
                delay(560)
                MailAuth.markPassed(ctx)
                onPassed()
            } else {
                state = VState.FAIL
                errMsg = r.message
                // 失败：重摇 + 双短震
                shakeStrength = 1f
                shakeTick++
                Haptics.error(ctx)
                delay(1400)
                code = ""
                state = VState.IDLE
            }
        }
    }

    fun resend() {
        if (state == VState.CHECKING || state == VState.OK) return
        if (MailAuth.inCooldown()) {
            errMsg = "请等待 " + MailAuth.cooldownSeconds() + " 秒后重试"
            return
        }
        val target = MailAuth.targetEmail()
        if (target.isBlank()) return
        state = VState.CHECKING
        errMsg = ""
        scope.launch {
            val cfg = SmtpMailer.Config(
                user = MailAuth.SENDER,
                authCode = MailAuth.AUTH_CODE,
                fromName = MailAuth.SENDER_NAME
            )
            val newCode = Codes.mail(6)
            val r = withContext(Dispatchers.IO) {
                SmtpMailer.send(cfg, target, MailAuth.SUBJECT, MailAuth.bodyFor(newCode))
            }
            if (r.ok) {
                MailAuth.issue(target, newCode)
                errMsg = "验证码已重新发送"
                code = ""
                state = VState.IDLE
            } else {
                state = VState.FAIL
                errMsg = r.message
                Haptics.error(ctx)
            }
        }
    }

    fun onKey(digit: Char) {
        if (state != VState.IDLE) return
        if (code.length >= CardKey.LEN) return
        code += digit
        if (code.length == CardKey.LEN) submit()
    }

    fun onDelete() {
        if (state != VState.IDLE) return
        if (code.isNotEmpty()) code = code.dropLast(1)
    }

    val activeColor = when (state) {
        VState.OK -> Good
        VState.FAIL -> Bad
        else -> Accent
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(72.dp))

        // 顶部图标：矢量绘制
        Box(
            Modifier
                .size(72.dp)
                .background(PaperSoft, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            val iconScale by animateFloatAsState(
                targetValue = when (state) {
                    VState.OK -> 1.06f
                    VState.FAIL -> 0.96f
                    else -> 1f
                },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "icon-scale"
            )
            when (state) {
                VState.OK -> CheckGlyph(Good, Modifier.size(34.dp).scale(iconScale))
                VState.FAIL -> CrossGlyph(Bad, Modifier.size(30.dp).scale(iconScale))
                else -> LockGlyph(Accent, Modifier.size(32.dp).scale(iconScale))
            }
        }

        Spacer(Modifier.height(22.dp))
        Text("输入验证码", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(7.dp))
        val target = MailAuth.targetEmail()
        Text(
            if (target.isBlank()) "请输入邮箱收到的 6 位验证码"
            else "验证码已发送至\n" + target,
            fontSize = 12.5.sp, color = InkSoft,
            textAlign = TextAlign.Center, lineHeight = 18.sp
        )

        Spacer(Modifier.height(28.dp))

        // 六格显示
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            for (i in 0 until CardKey.LEN) {
                CodeCell(
                    index = i,
                    char = code.getOrNull(i),
                    focused = (i == code.length && state == VState.IDLE),
                    filled = i < code.length,
                    state = state,
                    shakeTick = shakeTick,
                    shakeStrength = shakeStrength
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 状态文字
        Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
            when (state) {
                VState.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 12.dp, color = Accent)
                    Spacer(Modifier.width(7.dp))
                    Text("正在验证...", fontSize = 12.sp, color = InkSoft)
                }
                VState.OK -> Text(
                    "验证通过", fontSize = 12.5.sp,
                    color = Good, fontWeight = FontWeight.Medium
                )
                VState.FAIL -> Text(errMsg, fontSize = 12.5.sp, color = Bad)
                else -> {}
            }
        }

        Spacer(Modifier.weight(1f))

        // ===================== 内置数字键盘 =====================
        NumberPad(
            enabled = state == VState.IDLE,
            onDigit = { onKey(it) },
            onDelete = { onDelete() }
        )

        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                if (MailAuth.inCooldown())
                    "重新发送（" + MailAuth.cooldownSeconds() + "s）"
                else "重新发送",
                fontSize = 12.sp,
                color = if (state == VState.IDLE) Accent else InkFaint,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.pressable(
                    pressedScale = 0.95f,
                    enabled = state == VState.IDLE
                ) { resend() }
            )
        }
        Spacer(Modifier.height(18.dp))
    }
}

// ============================================================
// 内置数字键盘
// ============================================================

@Composable
private fun NumberPad(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "del")
    )

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                row.forEach { key ->
                    Box(Modifier.weight(1f)) {
                        when (key) {
                            "" -> Spacer(Modifier.height(58.dp))
                            "del" -> DeleteKey(enabled) { onDelete() }
                            else -> DigitKey(key[0], enabled) { onDigit(key[0]) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DigitKey(digit: Char, enabled: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val press = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .scale(press.value)
            .background(PaperSoft, RoundedCornerShape(16.dp))
            .pressable(
                pressedScale = 1f,
                enabled = enabled
            ) {
                // 强震动反馈
                Haptics.key(ctx)
                scope.launch {
                    press.snapTo(0.94f)
                    press.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    )
                }
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            digit.toString(),
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) Ink else InkFaint
        )
    }
}

@Composable
private fun DeleteKey(enabled: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val press = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .scale(press.value)
            .pressable(pressedScale = 1f, enabled = enabled) {
                // 强震动反馈
                Haptics.key(ctx)
                scope.launch {
                    press.snapTo(0.94f)
                    press.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                }
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        BackspaceGlyph(
            color = if (enabled) Ink else InkFaint,
            modifier = Modifier.size(width = 26.dp, height = 19.dp)
        )
    }
}

/** 退格图标：箭头 + 叉 */
@Composable
private fun BackspaceGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = h * 0.13f

        // 左侧箭头外形
        val body = Path().apply {
            moveTo(w * 0.32f, h * 0.10f)
            lineTo(w * 0.98f, h * 0.10f)
            lineTo(w * 0.98f, h * 0.90f)
            lineTo(w * 0.32f, h * 0.90f)
            lineTo(w * 0.02f, h * 0.50f)
            close()
        }
        drawPath(
            path = body,
            color = color,
            style = Stroke(width = stroke, join = StrokeJoin.Round, cap = StrokeCap.Round)
        )

        // 内部叉
        drawLine(
            color = color,
            start = Offset(w * 0.52f, h * 0.34f),
            end = Offset(w * 0.78f, h * 0.66f),
            strokeWidth = stroke, cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w * 0.78f, h * 0.34f),
            end = Offset(w * 0.52f, h * 0.66f),
            strokeWidth = stroke, cap = StrokeCap.Round
        )
    }
}

// ============================================================
// 单个字符格子
// ============================================================

@Composable
private fun CodeCell(
    index: Int,
    char: Char?,
    focused: Boolean,
    filled: Boolean,
    state: VState,
    shakeTick: Int,
    shakeStrength: Float
) {
    // 填入弹跳
    val pop = remember { Animatable(1f) }
    LaunchedEffect(char) {
        if (char != null) {
            pop.snapTo(0.80f)
            pop.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
    }

    // 摇一摇：每格独立、错峰
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeTick) {
        if (shakeTick > 0) {
            delay(index * 52L)
            val amp = shakeStrength
            shake.snapTo(0f)
            shake.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = if (amp > 0.7f) 400 else 300
                    0f at 0
                    (-1f * amp) at 55
                    (1f * amp) at 115
                    (-0.85f * amp) at 170
                    (0.85f * amp) at 225
                    (-0.45f * amp) at 280
                    (0.45f * amp) at 330
                    0f at (if (amp > 0.7f) 400 else 300)
                }
            )
        }
    }

    val borderColor = when {
        state == VState.FAIL -> Bad
        state == VState.OK -> Good
        focused -> Accent
        filled -> InkFaint
        else -> Line
    }
    val bg = when {
        state == VState.FAIL -> Bad.copy(alpha = 0.06f)
        state == VState.OK -> Good.copy(alpha = 0.06f)
        focused -> Accent.copy(alpha = 0.05f)
        else -> PaperSoft
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 44.dp, height = 56.dp)
                .scale(pop.value)
                .graphicsLayer { translationX = shake.value * 8f }
                .background(bg, RoundedCornerShape(12.dp))
                .border(
                    width = if (focused) 1.8.dp else 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                char?.toString() ?: "",
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    state == VState.FAIL -> Bad
                    state == VState.OK -> Good
                    else -> Ink
                }
            )
        }

        Spacer(Modifier.height(5.dp))

        val barWidth by animateFloatAsState(
            targetValue = if (focused) 20f else 6f,
            animationSpec = tween(Motion.NORMAL),
            label = "bar"
        )
        Box(
            Modifier
                .width(barWidth.dp)
                .height(2.dp)
                .background(
                    when {
                        focused -> Accent
                        state == VState.FAIL -> Bad
                        state == VState.OK -> Good
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(1.dp)
                )
        )
    }
}

// ============================================================
// 矢量图形
// ============================================================

@Composable
private fun LockGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f

        val ringW = w * 0.52f
        val ringH = h * 0.46f
        val ringLeft = (w - ringW) / 2f
        val ringTop = h * 0.06f
        drawArc(
            color = color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(ringLeft, ringTop), size = Size(ringW, ringH),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawLine(
            color = color, start = Offset(ringLeft, ringTop + ringH / 2f),
            end = Offset(ringLeft, ringTop + ringH * 0.86f),
            strokeWidth = stroke, cap = StrokeCap.Round
        )
        drawLine(
            color = color, start = Offset(ringLeft + ringW, ringTop + ringH / 2f),
            end = Offset(ringLeft + ringW, ringTop + ringH * 0.86f),
            strokeWidth = stroke, cap = StrokeCap.Round
        )

        val bodyTop = h * 0.44f
        val bodyH = h * 0.50f
        drawRoundRect(
            color = color, topLeft = Offset(w * 0.16f, bodyTop),
            size = Size(w * 0.68f, bodyH),
            cornerRadius = CornerRadius(w * 0.12f)
        )
        val holeR = w * 0.075f
        drawCircle(color = Paper, radius = holeR, center = Offset(w / 2f, bodyTop + bodyH * 0.42f))
        drawRoundRect(
            color = Paper,
            topLeft = Offset(w / 2f - holeR * 0.42f, bodyTop + bodyH * 0.42f),
            size = Size(holeR * 0.84f, bodyH * 0.28f),
            cornerRadius = CornerRadius(holeR * 0.42f)
        )
    }
}

@Composable
private fun CheckGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.10f, h * 0.54f)
            lineTo(w * 0.38f, h * 0.82f)
            lineTo(w * 0.90f, h * 0.20f)
        }
        drawPath(path = p, color = color, style = Stroke(width = w * 0.11f, cap = StrokeCap.Round))
    }
}

@Composable
private fun CrossGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.11f
        drawLine(
            color = color, start = Offset(w * 0.18f, h * 0.18f),
            end = Offset(w * 0.82f, h * 0.82f),
            strokeWidth = stroke, cap = StrokeCap.Round
        )
        drawLine(
            color = color, start = Offset(w * 0.82f, h * 0.18f),
            end = Offset(w * 0.18f, h * 0.82f),
            strokeWidth = stroke, cap = StrokeCap.Round
        )
    }
}
