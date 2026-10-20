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
 * 登录页（App 启动首屏）。
 *
 * 流程：
 *   登录成功 → onLoggedIn()
 *   「注册」→ onGoRegister()
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onGoRegister: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var msgBad by remember { mutableStateOf(false) }

    fun doLogin() {
        if (busy) return
        if (account.isBlank() || password.isBlank()) {
            msg = "请输入账号和密码"
            msgBad = true
            Haptics.error(ctx)
            return
        }
        busy = true
        msg = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) { AuthApi.login(account.trim(), password) }
            busy = false
            if (r.ok && AuthApi.persistLogin(ctx, r)) {
                msg = "登录成功"
                msgBad = false
                onLoggedIn()
            } else {
                msg = if (r.error.isNotEmpty()) r.error else "登录失败"
                msgBad = true
                Haptics.error(ctx)
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

        // 图标
        Box(
            Modifier.size(68.dp).background(PaperSoft, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("⌘", fontSize = 30.sp, color = Accent)
        }

        Spacer(Modifier.height(22.dp))
        Text("登录", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text("登录后同步你的账号信息", fontSize = 12.sp, color = InkSoft)

        Spacer(Modifier.height(30.dp))

        OutlinedTextField(
            value = account,
            onValueChange = { account = it },
            label = { Text("用户名或邮箱") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(22.dp))

        PrimaryButton(
            text = if (busy) "登录中..." else "登录",
            enabled = !busy,
            onClick = { doLogin() }
        )

        Spacer(Modifier.height(6.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { onGoRegister() }, enabled = !busy) {
                Text("注册新账号", fontSize = 13.sp, color = Accent)
            }
        }

        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                msg,
                fontSize = 12.sp,
                color = if (msgBad) Bad else Good,
                lineHeight = 18.sp
            )
        }

        Spacer(Modifier.height(60.dp))
    }
}

/** 通用主按钮 */
@Composable
internal fun PrimaryButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val bg = if (enabled) Accent else Line
    val fg = if (enabled) Color.White else InkSoft
    Box(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(bg, RoundedCornerShape(12.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

/** 横向分隔（带文字） */
@Composable
internal fun OrDivider() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(Line))
        Text("或", fontSize = 11.sp, color = InkSoft, modifier = Modifier.padding(horizontal = 12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Line))
    }
}
