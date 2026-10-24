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
import androidx.compose.ui.graphics.Color
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
import sy.great.toolbox.tools.AccountStore
import sy.great.toolbox.tools.AuthApi
import sy.great.toolbox.tools.AvatarStore
import sy.great.toolbox.tools.RankApi
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
    /** 「我的」→ 隐私设置 */
    onPrivacy: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf(AuthApi.username(ctx)) }
    var email by remember { mutableStateOf(AuthApi.email(ctx)) }
    var uid0 by remember { mutableStateOf(AuthApi.uid0(ctx)) }
    var showSwitch by remember { mutableStateOf(false) }
    var switchBusy by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf(AccountStore.list(ctx)) }

    // 管理员打的标签
    var tagText by remember { mutableStateOf("") }
    var tagColor by remember { mutableStateOf("") }
    var tagFxColors by remember { mutableStateOf<List<String>>(emptyList()) }

    var avatarVer by remember { mutableIntStateOf(AuthApi.avatarAt(ctx)) }
    var avatarBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var msgBad by remember { mutableStateOf(false) }

    // 签到
    var checkinBusy by remember { mutableStateOf(false) }

    /**
     * 执行签到。
     *
     * 注意：必须声明在 msg / msgBad 之后：
     * Kotlin 局部变量不能向前引用，否则报 Unresolved reference。
     */
    fun doCheckin() {
        if (checkinBusy) return
        checkinBusy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    RankApi.checkin(ctx.applicationContext)
                } catch (e: Throwable) {
                    RankApi.CheckinResult(false, e.message ?: "签到失败")
                }
            }
            checkinBusy = false

            if (r.ok) {
                val old = sy.great.toolbox.tools.SyncCenter.currentStat
                sy.great.toolbox.tools.SyncCenter.applyStat(
                    sy.great.toolbox.tools.SyncCenter.Stat(
                        points = (old?.points ?: 0) + r.gain,
                        streak = r.streak,
                        total = r.total,
                        canCheckin = false,
                        exp = old?.exp ?: 0,
                        level = r.level,
                        expBase = old?.expBase ?: 0,
                        expNext = old?.expNext ?: 100,
                    )
                )

                r.user?.let { u ->
                    try { AuthApi.syncUser(ctx, u) } catch (_: Throwable) {}
                }

                msg = "签到成功 +" + r.gain + " 分（连续 " + r.streak + " 天）"
                msgBad = false

                // 拉一次完整数据，刷新等级/经验条
                withContext(Dispatchers.IO) {
                    try { sy.great.toolbox.tools.SyncCenter.syncNow(ctx.applicationContext) } catch (_: Throwable) {}
                }
            } else {
                msg = r.error.ifBlank { "签到失败" }
                msgBad = true
            }
        }
    }

    // 从本地缓存加载头像（断网也能显示）
    fun loadLocalAvatar() {
        val uid = AuthApi.uid(ctx)
        if (uid <= 0) return
        avatarBitmap = AvatarStore.loadLocal(ctx, uid)
    }

    /**
     * 切换账号后重新读一遍本地身份。
     *
     * 必须定义在 loadLocalAvatar / avatarVer 之后：
     * Kotlin 局部函数按声明顺序解析，向前引用会编译失败。
     */
    fun refreshIdentity() {
        username = AuthApi.username(ctx)
        email = AuthApi.email(ctx)
        uid0 = AuthApi.uid0(ctx)
        avatarVer = AuthApi.avatarAt(ctx)
        accounts = AccountStore.list(ctx)
        loadLocalAvatar()
    }

    LaunchedEffect(Unit) {
        loadLocalAvatar()

        // 进入「我的」就同步一次，保证签到按钮可点
        withContext(Dispatchers.IO) {
            try { sy.great.toolbox.tools.SyncCenter.syncNow(ctx.applicationContext) } catch (_: Throwable) {}
        }

        // 同步服务端最新信息
        val r = withContext(Dispatchers.IO) { AuthApi.me(ctx) }
        if (r.ok) {
            val u = r.obj("user")
            if (u != null) {
                AuthApi.syncUser(ctx, u)
                username = u.optString("username", username)
                email = u.optString("email", email)
                uid0 = u.optString("uid", uid0)
                avatarVer = u.optInt("avatar_at", 0)
                tagText = u.optString("tag_text", "")
                tagColor = u.optString("tag_color", "")

                // 炫彩流光颜色：后端是 JSON 数组字符串，如 ["#FF5F6D","#FFC371"]
                val fxRaw = u.optString("tag_fx", "")
                tagFxColors = parseFxColors(fxRaw)

                // 同步写入 SyncCenter，让其他地方（如首页）也能直接读
                if (tagText.isNotBlank()) {
                    sy.great.toolbox.tools.SyncCenter.applyTag(
                        sy.great.toolbox.tools.SyncCenter.Tag(
                            text = tagText,
                            color = tagColor,
                            fxColors = tagFxColors,
                        )
                    )
                } else {
                    sy.great.toolbox.tools.SyncCenter.applyTag(null)
                }
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
                        // 名字样式（来自后台设置）：
                        //   mode 决定「主名字」显示哪一项
                        //   color / fxColors 对下面三行全部生效
                        val nameStyle = sy.great.toolbox.tools.SyncCenter.currentNameStyle

                        // 优先用 sync 下发的实时值（后台改名改邮箱后无需重登）；
                        // 为空时回退到本地缓存
                        val liveName = nameStyle.displayName
                        val liveUid = nameStyle.uid0.ifBlank { uid0 }
                        val liveMail = nameStyle.email.ifBlank { email }

                        val displayName = when {
                            liveName.isNotBlank() -> liveName
                            nameStyle.mode == "uid" -> uid0.ifBlank { "-----" }
                            nameStyle.mode == "email" -> email.ifBlank { "未登录" }
                            else -> username.ifBlank { "未登录" }
                        }

                        // 主名字：大号
                        sy.great.toolbox.ui.component.FxText(
                            text = displayName,
                            color = nameStyle.color,
                            fxColors = nameStyle.fxColors,
                            fontSize = 18,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )

                        Spacer(Modifier.height(4.dp))

                        // UID 行：始终显示 UID，但应用同一套样式
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "UID",
                                fontSize = 11.sp,
                                color = InkSoft,
                            )
                            Spacer(Modifier.width(5.dp))
                        
                            sy.great.toolbox.ui.component.FxText(
                                text = liveUid.ifBlank { "-----" },
                                color = nameStyle.color,
                                fxColors = nameStyle.fxColors,
                                fontSize = 12,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        // 邮箱行：始终显示邮箱，但应用同一套样式
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            sy.great.toolbox.ui.component.FxText(
                                text = liveMail.ifBlank { "—" },
                                color = nameStyle.color,
                                fxColors = nameStyle.fxColors,
                                fontSize = 12,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            // 称号：支持炫彩流光（数据来自 SyncCenter，实时更新）
                            val liveTag = sy.great.toolbox.tools.SyncCenter.currentTag
                            val showTag = liveTag?.text?.isNotBlank() == true || tagText.isNotBlank()

                            if (showTag) {
                                Spacer(Modifier.width(8.dp))

                                // 优先 SyncCenter（实时同步来的），
                                // 否则用 me() 拉到的本地字段（同样含 tagFxColors）
                                val badgeTag = liveTag ?: sy.great.toolbox.tools.SyncCenter.Tag(
                                    text = tagText,
                                    color = tagColor,
                                    fxColors = tagFxColors,
                                )

                                sy.great.toolbox.ui.component.TagBadge(
                                    tag = badgeTag,
                                    fontSize = 10,
                                )
                            }
                        }
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

            // ---- 签到卡 + 排行榜入口 ----
            item {
                Spacer(Modifier.height(12.dp))

                sy.great.toolbox.ui.component.CheckinCard(
                    stat = sy.great.toolbox.tools.SyncCenter.currentStat,
                    busy = checkinBusy,
                    modifier = Modifier.fillMaxWidth(),
                    onCheckin = { doCheckin() },
                )
            }

            // ---- 账号 ----
            item {
                Spacer(Modifier.height(12.dp))
                KsuCard {
                    // UID 只读：用 BasicComponent（无箭头、无点击）
                    top.yukonga.miuix.kmp.basic.BasicComponent(
                        title = "UID",
                        summary = uid0.ifBlank { "-----" },
                    )
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
                        title = "隐私设置",
                        summary = "控制排行榜上展示的信息",
                        onClick = onPrivacy,
                    )
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

            // ---- 切换账号（在退出登录下方）----
            item {
                Spacer(Modifier.height(12.dp))
                KsuCard {
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "切换账号",
                        summary = if (accounts.size > 1)
                            "已登录 ${accounts.size} 个账号，点击选择一个"
                        else
                            "登录其他账号，已保存的账号免密码",
                        onClick = { showSwitch = true },
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }

        // 账号选择弹窗。
        //
        // 必须放在 LazyColumn 外面：LazyListScope 的 lambda 不是 @Composable 上下文，
        // 在里面直接调 AlertDialog / Text 会报
        // “@Composable invocations can only happen from the context of a @Composable function”。
        if (showSwitch) {
            AccountPickerDialog(
                accounts = accounts,
                currentUid = uid0,
                busy = switchBusy,
                onDismiss = { showSwitch = false },
                onPick = { acc ->
                    switchBusy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            try {
                                AuthApi.switchTo(ctx, acc)
                            } catch (e: Throwable) {
                                AuthApi.Resp(false, e.message ?: "切换失败")
                            }
                        }
                        switchBusy = false
                        if (r.ok) {
                            showSwitch = false
                            refreshIdentity()
                            msg = "已切换到 ${acc.label}"
                            msgBad = false
                        } else {
                            // token 已失效：从历史里移除
                            AccountStore.remove(ctx, acc.uid0)
                            accounts = AccountStore.list(ctx)
                            msg = r.error.ifBlank { "该账号登录已失效" }
                            msgBad = true
                        }
                    }
                },
                onRemove = { acc ->
                    AccountStore.remove(ctx, acc.uid0)
                    accounts = AccountStore.list(ctx)
                },
                onAddOther = {
                    showSwitch = false
                    onLogout()
                },
            )
        }
    }
}

/**
 * 账号选择弹窗。
 *
 * 列表列出所有已登录过的账号（当前账号打勾），
 * 点一下直接切过去，不需要输密码。
 * 底部一个「登录其他账号」→ 走完整登录流程。
 */
@Composable
private fun AccountPickerDialog(
    accounts: List<AccountStore.Account>,
    currentUid: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onPick: (AccountStore.Account) -> Unit,
    onRemove: (AccountStore.Account) -> Unit,
    onAddOther: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "切换账号",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (accounts.isEmpty()) {
                    Text(
                        "还没有保存的账号。",
                        fontSize = 13.sp,
                        color = InkSoft,
                    )
                } else {
                    accounts.forEach { acc ->
                        val isCurrent = acc.uid0 == currentUid
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = !busy && !isCurrent) { onPick(acc) }
                                .padding(horizontal = 10.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 头像占位：用首字母
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(if (isCurrent) Accent.copy(alpha = 0.15f) else LocalPalette.current.paperSoft),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    acc.label.take(1).uppercase(),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isCurrent) Accent else InkSoft,
                                )
                            }

                            Spacer(Modifier.width(11.dp))

                            Column(Modifier.weight(1f)) {
                                Text(
                                    acc.label,
                                    fontSize = 14.sp,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    color = Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    buildString {
                                        append("UID ")
                                        append(acc.uid0.ifBlank { "-----" })
                                        if (isCurrent) append("　·　当前账号")
                                    },
                                    fontSize = 11.5.sp,
                                    color = if (isCurrent) Accent else InkSoft,
                                    maxLines = 1,
                                )
                            }

                            if (!isCurrent) {
                                Text(
                                    "移除",
                                    fontSize = 12.sp,
                                    color = InkSoft,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(enabled = !busy) { onRemove(acc) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                Text(
                    if (busy) "切换中..." else "＋ 登录其他账号",
                    fontSize = 13.5.sp,
                    color = if (busy) InkSoft else Accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !busy) { onAddOther() }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("取消", color = InkSoft)
            }
        },
    )
}

/**
 * 解析后端返回的 tag_fx（JSON 数组字符串）。
 *
 * 格式：["#FF5F6D","#FFC371"]，最多 5 个。
 * 容错：空串 / 非法 JSON / 非法颜色 → 返回空列表（不开流光）。
 */
private fun parseFxColors(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return try {
        val arr = org.json.JSONArray(raw)
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val c = arr.optString(i, "").trim().uppercase()
            if (c.length == 7 && c[0] == '#' && c.drop(1).all { it in "0123456789ABCDEF" }) {
                if (!out.contains(c)) out.add(c)
            }
            if (out.size >= 5) break
        }
        if (out.size >= 2) out else emptyList()
    } catch (_: Throwable) {
        emptyList()
    }
}

/** 把 #RRGGBB 解析成 Color；失败回退灰色 */
private fun parseTagColor(hex: String): Color {
    return try {
        if (hex.length == 7 && hex[0] == '#') {
            Color(android.graphics.Color.parseColor(hex))
        } else Color(0xFF9A9A9A)
    } catch (_: Throwable) {
        Color(0xFF9A9A9A)
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
