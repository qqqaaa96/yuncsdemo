package sy.great.toolbox.ui.pages

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import sy.great.toolbox.ui.PageHeader
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.Good
import sy.great.toolbox.ui.theme.Ink
import sy.great.toolbox.ui.theme.InkSoft
import sy.great.toolbox.ui.theme.LocalPalette

/** 子页通用外壳 */
@Composable
private fun PageShell(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val pal = LocalPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .background(pal.paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        PageHeader(title = title, onBack = onBack)
        Spacer(Modifier.height(16.dp))
        content()
        Spacer(Modifier.height(60.dp))
    }
}

/** 主按钮 */
@Composable
private fun BigButton(
    text: String,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val bg = when {
        !enabled -> LocalPalette.current.line
        danger -> Bad
        else -> Accent
    }
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

// ============================================================
// 修改昵称
// ============================================================
@Composable
fun ChangeUsernamePage(
    currentName: String,
    onDone: (String) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(currentName) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }

    fun submit() {
        val n = name.trim()
        if (n == currentName) {
            msg = "昵称未改变"
            bad = true
            return
        }
        if (n.length < 3 || n.length > 20) {
            msg = "用户名需 3~20 位"
            bad = true
            return
        }
        if (!n.matches(Regex("^[A-Za-z0-9_]+$"))) {
            msg = "只能包含字母、数字、下划线"
            bad = true
            return
        }
        if (n.matches(Regex("^[0-9]+$"))) {
            msg = "不能全是数字"
            bad = true
            return
        }
        busy = true
        msg = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) { AuthApi.changeUsername(ctx, n) }
            busy = false
            if (r.ok) {
                r.obj("user")?.let { AuthApi.syncUser(ctx, it) }
                onDone(n)
            } else {
                msg = if (r.error.isNotEmpty()) r.error else "修改失败"
                bad = true
            }
        }
    }

    PageShell("修改昵称", onBack) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("用户名") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text("3~20 位，仅字母、数字、下划线，不能全是数字", fontSize = 11.sp, color = InkSoft)
        Spacer(Modifier.height(20.dp))
        BigButton(if (busy) "保存中..." else "保存", !busy) { submit() }
        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(msg, fontSize = 12.sp, color = if (bad) Bad else Good)
        }
    }
}

// ============================================================
// 换绑邮箱
// ============================================================
@Composable
fun ChangeEmailPage(
    currentEmail: String,
    onDone: (String) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }

    fun sendCode() {
        val e = email.trim()
        if (!e.contains("@") || !e.contains(".")) {
            msg = "请输入有效邮箱"
            bad = true
            return
        }
        busy = true
        msg = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) { AuthApi.changeEmailSend(ctx, e) }
            busy = false
            if (r.ok) {
                sent = true
                msg = "验证码已发送到 " + e
                bad = false
            } else {
                msg = if (r.error.isNotEmpty()) r.error else "发送失败"
                bad = true
            }
        }
    }

    fun confirm() {
        val e = email.trim()
        if (code.length != 6) {
            msg = "请输入 6 位验证码"
            bad = true
            return
        }
        busy = true
        msg = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) { AuthApi.changeEmailConfirm(ctx, e, code) }
            busy = false
            if (r.ok) {
                r.obj("user")?.let { AuthApi.syncUser(ctx, it) }
                onDone(e)
            } else {
                msg = if (r.error.isNotEmpty()) r.error else "换绑失败"
                bad = true
            }
        }
    }

    PageShell("换绑邮箱", onBack) {
        Text("当前邮箱：" + currentEmail, fontSize = 12.sp, color = InkSoft)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("新邮箱") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )

        if (!sent) {
            Spacer(Modifier.height(20.dp))
            BigButton(if (busy) "发送中..." else "发送验证码", !busy) { sendCode() }
        } else {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("6 位验证码") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
            BigButton(if (busy) "提交中..." else "确认换绑", !busy && code.length == 6) { confirm() }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { sendCode() }, enabled = !busy) {
                    Text("重新发送", fontSize = 13.sp, color = Accent)
                }
            }
        }

        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(msg, fontSize = 12.sp, color = if (bad) Bad else Good)
        }
    }
}

// ============================================================
// 注销账号
// ============================================================
@Composable
fun DeleteAccountPage(
    username: String,
    onDeleted: () -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    fun doDelete() {
        busy = true
        msg = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) { AuthApi.deleteAccount(ctx, password) }
            busy = false
            if (r.ok) {
                AuthApi.clearToken(ctx)
                onDeleted()
            } else {
                msg = if (r.error.isNotEmpty()) r.error else "注销失败"
                bad = true
            }
        }
    }

    PageShell("注销账号", onBack) {
        Text(
            "注销后账号与头像将被永久删除，无法恢复。",
            fontSize = 13.sp,
            color = Bad,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "账号：" + username,
            fontSize = 12.sp,
            color = InkSoft
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("请输入密码确认") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))
        BigButton(
            if (busy) "处理中..." else "注销账号",
            !busy && password.isNotBlank(),
            danger = true
        ) { showConfirm = true }

        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(msg, fontSize = 12.sp, color = if (bad) Bad else Good)
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("确认注销？") },
            text = { Text("此操作不可恢复。账号 " + username + " 将被永久删除。") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    doDelete()
                }) { Text("确定注销", color = Bad) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("取消") }
            }
        )
    }
}
