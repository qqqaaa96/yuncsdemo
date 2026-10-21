package sy.great.toolbox.ui.pages

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sy.great.toolbox.tools.AuthApi
import sy.great.toolbox.tools.AvatarStore
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.Good
import sy.great.toolbox.ui.theme.Ink
import sy.great.toolbox.ui.theme.InkSoft
import sy.great.toolbox.ui.theme.LocalPalette

/**
 * 「我的」页面。
 *
 * 结构（市面通用模板）：
 *   1. 头像 + 用户名 + 邮箱
 *   2. 账号信息
 *   3. 设置区（原设置页全部入口）
 *   4. 退出登录
 */
@Composable
fun ProfilePage(
    onCheckUpdate: () -> Unit,
    onTheme: () -> Unit,
    onAbout: () -> Unit,
    onLogout: () -> Unit,
    onChangeUsername: () -> Unit = {},
    onChangeEmail: () -> Unit = {},
    onDeleteAccount: () -> Unit = {},
    /** 「我的」→ 意见反馈 */
    onFeedback: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf(AuthApi.username(ctx)) }
    var email by remember { mutableStateOf(AuthApi.email(ctx)) }
    var avatarVer by remember { mutableIntStateOf(AuthApi.avatarAt(ctx)) }
    var avatarBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var msgBad by remember { mutableStateOf(false) }

    // 从本地缓存加载头像（断网也能显示）
    fun loadLocalAvatar() {
        val uid = AuthApi.uid(ctx)
        if (uid <= 0) return
        avatarBitmap = AvatarStore.loadLocal(ctx, uid)
    }

    LaunchedEffect(Unit) {
        loadLocalAvatar()

        // 同步服务端最新信息
        val r = withContext(Dispatchers.IO) { AuthApi.me(ctx) }
        if (r.ok) {
            val u = r.obj("user")
            if (u != null) {
                AuthApi.syncUser(ctx, u)
                username = u.optString("username", username)
                email = u.optString("email", email)
                avatarVer = u.optInt("avatar_at", 0)
            }
        }

        // 关键：只要服务端有头像，就确保本地缓存存在。
        //
        // 之前只判断 “版本变了才拉” —— 重装 App 后本地版本是 0，
        // 如果 me() 那次请求失败，就永远不会去拉，头像一直是默认的。
        // 现在改成：本地没有缓存文件就拉，不再只看版本号。
        val uid = AuthApi.uid(ctx)
        if (uid > 0 && avatarVer > 0) {
            val cached = withContext(Dispatchers.IO) { AvatarStore.hasLocal(ctx, uid) }
            if (!cached) {
                withContext(Dispatchers.IO) {
                    AvatarStore.fetchAndCache(ctx, uid, avatarVer)
                }
                loadLocalAvatar()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        msg = ""
        scope.launch {
            val jpeg = withContext(Dispatchers.IO) {
                AvatarStore.pickAndCompress(ctx.contentResolver, uri, 512)
            }
            if (jpeg == null) {
                busy = false
                val why = AvatarStore.lastError
                msg = if (why.isNotEmpty()) "图片读取失败：$why" else "图片读取失败（格式不支持）"
                msgBad = true
                return@launch
            }

            val r = withContext(Dispatchers.IO) { AuthApi.uploadAvatar(ctx, jpeg) }
            busy = false

            if (r.ok) {
                val newVer = r.int("avatar_at")
                AuthApi.setAvatarAt(ctx, newVer)
                r.obj("user")?.let { AuthApi.syncUser(ctx, it) }
                withContext(Dispatchers.IO) {
                    AvatarStore.saveLocal(ctx, AuthApi.uid(ctx), jpeg)
                }
                avatarVer = newVer
                loadLocalAvatar()
                msg = "头像已更新"
                msgBad = false
            } else {
                msg = if (r.error.isNotEmpty()) r.error else "上传失败"
                msgBad = true
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(pal.paper)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
            overscrollEffect = null,
        ) {
            // ---- 顶部：头像 + 用户名 ----
            item {
                Spacer(Modifier.height(24.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(pal.paperSoft)
                            .clickable(enabled = !busy) { picker.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = avatarBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "头像",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                username.take(1).ifBlank { "?" },
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = InkSoft
                            )
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    Column(Modifier.weight(1f)) {
                        Text(
                            username.ifBlank { "未登录" },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            email.ifBlank { "—" },
                            fontSize = 12.sp,
                            color = InkSoft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (busy) "上传中..." else "点击头像更换",
                            fontSize = 11.sp,
                            color = if (busy) InkSoft else Accent
                        )
                    }
                }

                if (msg.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        msg,
                        fontSize = 12.sp,
                        color = if (msgBad) Bad else Good,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }

            // ---- 账号 ----
            item {
                Spacer(Modifier.height(12.dp))
                KsuCard {
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "用户名",
                        summary = username.ifBlank { "—" },
                        onClick = onChangeUsername,
                    )
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "邮箱",
                        summary = email.ifBlank { "—" },
                        onClick = onChangeEmail,
                    )
                }
            }

            // ---- 反馈 + 注销 ----
            item {
                KsuCard {
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "意见反馈",
                        summary = "问题反馈 / 功能建议",
                        onClick = onFeedback,
                    )
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "注销账号",
                        summary = "永久删除账号与数据",
                        onClick = onDeleteAccount,
                    )
                }
            }

            // ---- 设置（原设置页全部内容）----
            item {
                KsuCard {
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "检查更新",
                        summary = "查看是否有新版本",
                        onClick = onCheckUpdate,
                    )
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "主题设置",
                        summary = "外观 / 底栏 / 界面缩放",
                        onClick = onTheme,
                    )
                }
            }

            item {
                KsuCard {
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "关于",
                        summary = "版本 / 类型",
                        onClick = onAbout,
                    )
                }
            }

            // ---- 退出登录 ----
            item {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(pal.paperSoft, RoundedCornerShape(14.dp))
                        .clickable(enabled = !busy) { onLogout() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("退出登录", fontSize = 14.sp, color = Bad, fontWeight = FontWeight.Medium)
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun KsuCard(content: @Composable () -> Unit) {
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
        content = { content() },
    )
}
