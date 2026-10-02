package com.csdemo.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.csdemo.ui.theme.Warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 发送状态 */
private enum class SState { IDLE, SENDING, SENT, FAIL }

@Composable
fun MailVerifyScreen(onSent: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf(Codes.graphic()) }
    var state by remember { mutableStateOf(SState.IDLE) }
    var msg by remember { mutableStateOf("") }
    var shakeTick by remember { mutableStateOf(0) }

    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeTick) {
        if (shakeTick > 0) {
            shake.snapTo(0f)
            shake.animateTo(
                targetValue = 0f,
                animationSpec = androidx.compose.animation.core.keyframes {
                    durationMillis = 400
                    0f at 0
                    -1f at 55
                    1f at 115
                    -0.85f at 170
                    0.85f at 225
                    -0.45f at 280
                    0.45f at 330
                    0f at 400
                }
            )
        }
    }

    fun doSend() {
        if (state == SState.SENDING) return
        val mail = email.trim()
        if (!SmtpMailer.isEmail(mail)) {
            msg = "请输入有效的邮箱地址"
            state = SState.FAIL
            Haptics.error(ctx)
            shakeTick++
            return
        }
        if (!Codes.matchGraphic(input, captcha)) {
            msg = "图形验证码不正确"
            state = SState.FAIL
            Haptics.error(ctx)
            shakeTick++
            captcha = Codes.graphic()
            input = ""
            return
        }

        state = SState.SENDING
        msg = ""
        scope.launch {
            val code = Codes.mail(6)
            val cfg = SmtpMailer.Config(
                user = MailAuth.SENDER,
                authCode = MailAuth.AUTH_CODE,
                fromName = MailAuth.SENDER_NAME
            )
            val r = withContext(Dispatchers.IO) {
                SmtpMailer.send(
                    cfg = cfg,
                    to = mail,
                    subject = MailAuth.SUBJECT,
                    body = MailAuth.bodyFor(code)
                )
            }
            if (r.ok) {
                state = SState.SENT
                MailAuth.issue(mail, code)
                delay(700)
                onSent(mail)
            } else {
                state = SState.FAIL
                msg = r.message
                Haptics.error(ctx)
                shakeTick++
                captcha = Codes.graphic()
                input = ""
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp)
    ) {
        Spacer(Modifier.height(76.dp))

        // 图标
        Box(
            Modifier.size(68.dp).background(PaperSoft, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            MailGlyph(Accent, Modifier.size(32.dp))
        }

        Spacer(Modifier.height(22.dp))
        Text("邮箱验证", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(7.dp))
        Text(
            "验证码将发送到你填写的邮箱",
            fontSize = 12.5.sp, color = InkSoft, lineHeight = 18.sp
        )

        Spacer(Modifier.height(30.dp))

        // 邮箱
        FieldLabel("邮箱地址")
        Spacer(Modifier.height(8.dp))
        PlainField(
            value = email,
            onChange = { email = it; if (state == SState.FAIL) state = SState.IDLE },
            placeholder = "example@qq.com",
            keyboard = KeyboardType.Email,
            enabled = state != SState.SENDING && state != SState.SENT
        )

        Spacer(Modifier.height(18.dp))

        // 图形验证码
        FieldLabel("图形验证码")
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = shake.value * 8f },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.weight(1f)) {
                PlainField(
                    value = input,
                    onChange = { v ->
                        input = v.filter { it.isLetterOrDigit() }.take(4)
                        if (state == SState.FAIL) state = SState.IDLE
                    },
                    placeholder = "4 位验证码",
                    keyboard = KeyboardType.Ascii,
                    enabled = state != SState.SENDING && state != SState.SENT
                )
            }
            CaptchaBox(captcha) { captcha = Codes.graphic(); input = "" }
        }

        Spacer(Modifier.height(26.dp))

        // 发送按钮
        val btnColor = when (state) {
            SState.SENT -> Good
            SState.FAIL -> Bad
            else -> Accent
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(btnColor, RoundedCornerShape(14.dp))
                .pressable(
                    pressedScale = 0.98f,
                    enabled = state == SState.IDLE || state == SState.FAIL
                ) { doSend() },
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                SState.SENDING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 14.dp, color = Paper)
                    Spacer(Modifier.width(9.dp))
                    Text("正在发送...", fontSize = 15.sp, color = Paper,
                        fontWeight = FontWeight.Medium)
                }
                SState.SENT -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CheckGlyph2(Paper, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("验证码已发送", fontSize = 15.sp, color = Paper,
                        fontWeight = FontWeight.Medium)
                }
                else -> Text("发送验证码", fontSize = 15.sp, color = Paper,
                    fontWeight = FontWeight.Medium)
            }
        }

        // 状态提示
        Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) {
            when (state) {
                SState.SENT -> Text(
                    "验证码已发送至 " + email.trim(),
                    fontSize = 12.sp, color = Good, textAlign = TextAlign.Center
                )
                SState.FAIL -> Text(
                    msg, fontSize = 12.sp, color = Bad,
                    textAlign = TextAlign.Center, lineHeight = 17.sp
                )
                SState.SENDING -> Text(
                    "邮件发送需要几秒钟，请稍候",
                    fontSize = 11.5.sp, color = InkSoft
                )
                else -> {}
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "若长时间未收到，请检查垃圾邮件文件夹",
            fontSize = 10.5.sp, color = InkFaint, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(40.dp))
    }
}

// ------------------------------------------------------------

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 12.5.sp, color = InkSoft)
}

/** 简洁输入框（自带光标，不调系统输入法外观） */
@Composable
private fun PlainField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardType,
    enabled: Boolean
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(PaperSoft, RoundedCornerShape(12.dp))
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(placeholder, fontSize = 14.sp, color = InkFaint)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            cursorBrush = SolidColor(Accent),
            textStyle = TextStyle(fontSize = 14.sp, color = Ink),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 图形验证码块（可点击刷新） */
@Composable
private fun CaptchaBox(code: String, onRefresh: () -> Unit) {
    Box(
        Modifier
            .size(width = 108.dp, height = 50.dp)
            .background(PaperSoft, RoundedCornerShape(12.dp))
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .pressable(pressedScale = 0.96f, onClick = onRefresh),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // 干扰线
            val seed = code.hashCode()
            val rnd = java.util.Random(seed.toLong())
            for (i in 0 until 4) {
                val y1 = rnd.nextFloat() * h
                val y2 = rnd.nextFloat() * h
                drawLine(
                    color = Accent.copy(alpha = 0.25f),
                    start = Offset(0f, y1),
                    end = Offset(w, y2),
                    strokeWidth = 1.2f
                )
            }
            // 噪点
            for (i in 0 until 40) {
                drawCircle(
                    color = InkFaint.copy(alpha = 0.35f),
                    radius = 1.1f,
                    center = Offset(rnd.nextFloat() * w, rnd.nextFloat() * h)
                )
            }
        }
        // 字母（逐字错位，增加识别难度）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            code.forEachIndexed { i, c ->
                val offsetY = when (i) {
                    0 -> 2f
                    1 -> -3f
                    2 -> 3f
                    else -> -1f
                }
                Text(
                    c.toString(),
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = Accent,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.graphicsLayer { translationY = offsetY }
                )
            }
        }
    }
}

// ------------------------------------------------------------
// 矢量图标
// ------------------------------------------------------------

/** 信封图标 */
@Composable
private fun MailGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.085f
        val rect = androidx.compose.ui.geometry.Rect(
            left = w * 0.06f, top = h * 0.18f,
            right = w * 0.94f, bottom = h * 0.82f
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(rect.left, rect.top),
            size = androidx.compose.ui.geometry.Size(rect.width, rect.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
            style = Stroke(width = stroke)
        )
        // 信封舌
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(rect.left, rect.top + h * 0.04f)
            lineTo(w * 0.5f, h * 0.56f)
            lineTo(rect.right, rect.top + h * 0.04f)
        }
        drawPath(p, color = color, style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

/** 小白对勾（按钮内用） */
@Composable
private fun CheckGlyph2(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.12f, h * 0.52f)
            lineTo(w * 0.38f, h * 0.80f)
            lineTo(w * 0.88f, h * 0.20f)
        }
        drawPath(p, color = color, style = Stroke(width = w * 0.16f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}
