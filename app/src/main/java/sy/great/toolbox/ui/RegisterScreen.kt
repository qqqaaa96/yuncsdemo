package sy.great.toolbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sy.great.toolbox.tools.AuthApi
import sy.great.toolbox.tools.DeviceId
import sy.great.toolbox.tools.Haptics
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.Good
import sy.great.toolbox.ui.theme.Ink
import sy.great.toolbox.ui.theme.InkSoft
import sy.great.toolbox.ui.theme.Line
import sy.great.toolbox.ui.theme.Paper
import sy.great.toolbox.ui.theme.PaperSoft

/**
 * 注册页。
 *
 * 流程：
 *   填写用户名 / 邮箱 / 密码 → 点「发送验证码」
 *   → 服务端发信成功 → onCodeSent(username, email, password)
 *   → 进入验证码输入页
 */
@Composable
fun RegisterScreen(
    onCodeSent: (username: String, email: String, password: String) -> Unit,
    onBackToLogin: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }

    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var msgBad by remember { mutableStateOf(false) }

    // 图形验证码（服务端出题）
    var captchaId by remember { mutableStateOf("") }
    var captchaCode by remember { mutableStateOf("") }
    var captchaTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    fun checkInput(): String? {
        val u = username.trim()
        val e = email.trim()
        if (u.length < 3 || u.length > 20) return "用户名需 3~20 位"
        if (!u.matches(Regex("^[A-Za-z0-9_]+$"))) return "用户名只能包含字母、数字、下划线"
        if (u.matches(Regex("^[0-9]+$"))) return "用户名不能全是数字"
        if (!e.contains("@") || !e.contains(".")) return "请输入有效的邮箱"
        if (password.length < 8) return "密码至少 8 位"
        if (!password.matches(Regex(".*[A-Za-z].*"))) return "密码需包含字母"
        if (!password.matches(Regex(".*[0-9].*"))) return "密码需包含数字"
        if (password != password2) return "两次输入的密码不一致"
        if (captchaCode.length != 5) return "请输入图形验证码"
        return null
    }

    fun doSendCode() {
        if (busy) return
        val err = checkInput()
        if (err != null) {
            msg = err
            msgBad = true
            Haptics.error(ctx)
            return
        }
        busy = true
        msg = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                AuthApi.sendCode(
                    email = email.trim(),
                    purpose = "register",
                    captchaId = captchaId,
                    captchaCode = captchaCode
                )
            }
            busy = false
            if (r.ok) {
                msg = "验证码已发送到 " + email.trim()
                msgBad = false
                onCodeSent(username.trim(), email.trim(), password)
            } else {
                msg = if (r.error.isNotEmpty()) r.error else "发送失败"
                msgBad = true
                Haptics.error(ctx)
                // 图形码一次性，失败后必须刷新
                captchaCode = ""
                captchaTick++
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
        Spacer(Modifier.height(90.dp))

        Box(
            Modifier.size(68.dp).background(PaperSoft, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("✎", fontSize = 28.sp, color = Accent)
        }

        Spacer(Modifier.height(22.dp))
        Text("注册账号", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text("填写信息后发送邮箱验证码", fontSize = 12.sp, color = InkSoft)

        Spacer(Modifier.height(26.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("用户名") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("邮箱") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码（至少 8 位，含字母和数字）") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password2,
            onValueChange = { password2 = it },
            label = { Text("确认密码") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        // 服务端图形验证码：脚本无法绕过（本地不存答案）
        androidx.compose.runtime.key(captchaTick) {
            CaptchaRow(
                captchaId = captchaId,
                captchaCode = captchaCode,
                onCodeChange = { captchaCode = it },
                onLoaded = { captchaId = it },
                enabled = !busy
            )
        }

        Spacer(Modifier.height(22.dp))

        PrimaryButton(
            text = if (busy) "发送中..." else "发送验证码",
            enabled = !busy,
            onClick = { doSendCode() }
        )

        Spacer(Modifier.height(6.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            TextButton(onClick = { onBackToLogin() }, enabled = !busy) {
                Text("已有账号？去登录", fontSize = 13.sp, color = Accent)
            }
        }

        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(msg, fontSize = 12.sp, color = if (msgBad) Bad else Good, lineHeight = 18.sp)
        }

        Spacer(Modifier.height(60.dp))
    }
}

/**
 * 注册第二步：输入邮箱验证码。
 *
 * 复用现有的验证码风格（6 位数字）。
 * 验证通过后提交注册，成功后 onRegistered()。
 */
@Composable
fun RegisterVerifyScreen(
    username: String,
    email: String,
    password: String,
    onRegistered: () -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var msgBad by remember { mutableStateOf(false) }

    fun doRegister() {
        if (busy) return
        if (code.length != 6) {
            msg = "请输入 6 位验证码"
            msgBad = true
            Haptics.error(ctx)
            return
        }
        busy = true
        msg = ""
        scope.launch {
            val devId = withContext(Dispatchers.IO) { DeviceId.get(ctx) }
            val r = withContext(Dispatchers.IO) {
                AuthApi.register(username, email, password, code, devId)
            }
            if (r.ok && AuthApi.persistLogin(ctx, r)) {
                busy = false
                msg = "注册成功"
                msgBad = false
                onRegistered()
            } else {
                busy = false
                msg = if (r.error.isNotEmpty()) r.error else "注册失败"
                msgBad = true
                Haptics.error(ctx)
            }
        }
    }

    fun doResend() {
        if (busy) return
        busy = true
        msg = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) { AuthApi.sendCode(email, "register") }
            busy = false
            if (r.ok) {
                msg = "验证码已重新发送"
                msgBad = false
            } else {
                msg = if (r.error.isNotEmpty()) r.error else "发送失败"
                msgBad = true
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
        Spacer(Modifier.height(90.dp))

        Box(
            Modifier.size(68.dp).background(PaperSoft, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("✉", fontSize = 28.sp, color = Accent)
        }

        Spacer(Modifier.height(22.dp))
        Text("输入验证码", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text("验证码已发送至 " + email, fontSize = 12.sp, color = InkSoft)

        Spacer(Modifier.height(26.dp))

        OutlinedTextField(
            value = code,
            onValueChange = { s ->
                // 只允许数字，最多 6 位
                val t = s.filter { it.isDigit() }.take(6)
                code = t
            },
            label = { Text("6 位验证码") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(22.dp))

        PrimaryButton(
            text = if (busy) "提交中..." else "完成注册",
            enabled = !busy && code.length == 6,
            onClick = { doRegister() }
        )

        Spacer(Modifier.height(6.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { doResend() }, enabled = !busy) {
                Text("重新发送", fontSize = 13.sp, color = Accent)
            }
            Text("·", fontSize = 13.sp, color = Line)
            TextButton(onClick = { onBack() }, enabled = !busy) {
                Text("返回修改", fontSize = 13.sp, color = Accent)
            }
        }

        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(msg, fontSize = 12.sp, color = if (msgBad) Bad else Good, lineHeight = 18.sp)
        }

        Spacer(Modifier.height(60.dp))
    }
}
