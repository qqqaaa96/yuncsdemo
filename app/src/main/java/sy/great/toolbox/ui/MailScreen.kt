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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sy.great.toolbox.tools.MailApi
import sy.great.toolbox.ui.theme.LocalPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 邮箱（站内信）。
 *
 * 风格：企业级简约。
 *   · 白底、细分割线、无阴影无渐变
 *   · 未读用左侧小红点 + 加粗标题，不用背景大色块
 *   · 优先级只用一条左侧竖线表示，不堆彩色标签
 *   · 正文可选中复制
 */
@Composable
fun MailScreen(
    onBack: () -> Unit,
    /** 切换到另一个账号（由 MainActivity 清 token 并跳登录页） */
    onSwitchAccount: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<MailApi.Mail>>(emptyList()) }
    var unread by remember { mutableStateOf(0) }
    var opened by remember { mutableStateOf<MailApi.Mail?>(null) }

    suspend fun reload() {
        val r = withContext(Dispatchers.IO) { MailApi.list(ctx, 100) }
        loading = false
        if (!r.ok) {
            error = r.error
            return
        }
        error = ""
        items = r.items
        unread = r.unread
    }

    LaunchedEffect(Unit) { reload() }

    // 当前账号信息（UID + 用户名）
    val myUid = remember { sy.great.toolbox.tools.AuthApi.uid0(ctx) }
    val myName = remember { sy.great.toolbox.tools.AuthApi.username(ctx) }

    var showSwitch by remember { mutableStateOf(false) }

    // 切换账号确认
    if (showSwitch) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSwitch = false },
            title = { Text("切换账号？", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "当前账号 $myName（$myUid）将退出登录，需要重新输入其他账号的密码。",
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    color = pal.inkSoft,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showSwitch = false
                    onSwitchAccount()
                }) { Text("切换", color = pal.accent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showSwitch = false }) {
                    Text("取消", color = pal.inkSoft)
                }
            },
        )
    }

    // 详情页
    val detail = opened
    if (detail != null) {
        MailDetail(
            mail = detail,
            onBack = { opened = null },
            onDelete = {
                scope.launch {
                    withContext(Dispatchers.IO) { MailApi.delete(ctx, detail.id) }
                    opened = null
                    reload()
                }
            },
        )
        return
    }

    Column(Modifier.fillMaxSize().background(pal.paper)) {
        MailTopBar(
            title = "邮箱",
            subtitle = if (unread > 0) "$unread 封未读" else "",
            onBack = onBack,
            action = { showSwitch = true },
            actionLabel = "切换账号",
            secondAction = if (unread > 0) {
                {
                    scope.launch {
                        withContext(Dispatchers.IO) { MailApi.markAllRead(ctx) }
                        reload()
                    }
                }
            } else null,
            secondLabel = "全部已读",
        )

        // 账号信息条：UID + 用户名
        AccountBar(uid = myUid, name = myName)

        when {
            loading -> LoadingBox()

            error.isNotEmpty() -> EmptyBox(
                title = "加载失败",
                desc = error,
                action = "重试",
                onAction = {
                    loading = true
                    scope.launch { reload() }
                },
            )

            items.isEmpty() -> EmptyBox(
                title = "暂无邮件",
                desc = "系统通知和活动消息会出现在这里",
            )

            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { m ->
                    MailRow(m) {
                        // 先标记已读，再打开
                        if (!m.read) {
                            scope.launch {
                                withContext(Dispatchers.IO) { MailApi.markRead(ctx, m.id) }
                                reload()
                            }
                        }
                        opened = m
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

// ---------------- 顶栏 ----------------

@Composable
private fun MailTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    action: (() -> Unit)? = null,
    actionLabel: String = "",
    secondAction: (() -> Unit)? = null,
    secondLabel: String = "",
) {
    val pal = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(pal.paper)
            .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = pal.ink,
                modifier = Modifier.size(21.dp),
            )
        }

        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = pal.ink,
            )
            if (subtitle.isNotEmpty()) {
                Text(subtitle, fontSize = 11.sp, color = pal.inkSoft)
            }
        }

        if (secondAction != null) {
            Text(
                secondLabel,
                fontSize = 13.sp,
                color = pal.inkSoft,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = secondAction)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        if (action != null) {
            Text(
                actionLabel,
                fontSize = 13.sp,
                color = pal.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = action)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

/**
 * 账号信息条：UID + 用户名。
 *
 * 显示在邮箱列表顶部，让用户随时知道自己当前在哪个账号。
 * UID 用等宽字体、主色强调，方便对账。
 */
@Composable
private fun AccountBar(uid: String, name: String) {
    val pal = LocalPalette.current

    Row(
        Modifier
            .fillMaxWidth()
            .background(pal.paperSoft)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "UID",
            fontSize = 11.sp,
            color = pal.inkSoft,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = uid.ifEmpty { "-----" },
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = pal.accent,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            letterSpacing = 0.5.sp,
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(1.dp).height(11.dp).background(pal.line))
        Spacer(Modifier.width(12.dp))
        Text(
            text = name.ifEmpty { "未知账号" },
            fontSize = 12.5.sp,
            color = pal.inkSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

// ---------------- 列表行 ----------------

@Composable
private fun MailRow(mail: MailApi.Mail, onClick: () -> Unit) {
    val pal = LocalPalette.current

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        // 左侧：未读红点 / 优先级竖线
        Box(
            Modifier
                .width(3.dp)
                .height(if (mail.level == "normal") 0.dp else 34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    when (mail.level) {
                        "warning" -> pal.accent
                        "important" -> pal.accent.copy(alpha = 0.45f)
                        else -> pal.paper
                    }
                ),
        )

        Spacer(Modifier.width(if (mail.level == "normal") 3.dp else 10.dp))

        Column(Modifier.weight(1f)) {
            // 标题行：未读点 + 标题 + 时间
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!mail.read) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(pal.accent),
                    )
                    Spacer(Modifier.width(6.dp))
                }

                Text(
                    text = mail.title,
                    fontSize = 14.5.sp,
                    fontWeight = if (mail.read) FontWeight.Normal else FontWeight.SemiBold,
                    color = pal.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                Spacer(Modifier.width(8.dp))

                Text(
                    text = formatTime(mail.createdAt),
                    fontSize = 11.sp,
                    color = pal.inkSoft,
                )
            }

            Spacer(Modifier.height(4.dp))

            // 摘要：去掉换行，截断显示
            val summary = mail.body.replace(Regex("\\s+"), " ").trim()
            Text(
                text = summary,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = pal.inkSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (mail.sender.isNotEmpty() && mail.sender != "系统") {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "来自 ${mail.sender}",
                    fontSize = 11.sp,
                    color = pal.inkSoft.copy(alpha = 0.75f),
                )
            }
        }
    }

    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(pal.line.copy(alpha = 0.6f)))
}

// ---------------- 详情 ----------------

@Composable
private fun MailDetail(
    mail: MailApi.Mail,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    val pal = LocalPalette.current
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(pal.paper)) {
        MailTopBar(
            title = "",
            subtitle = "",
            onBack = onBack,
            action = { confirmDelete = true },
            actionLabel = "删除",
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            // 优先级标记
            if (mail.level != "normal") {
                val label = if (mail.level == "warning") "重要提醒" else "须知"
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(pal.accent.copy(alpha = 0.10f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(label, fontSize = 11.sp, color = pal.accent, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(12.dp))
            }

            // 标题
            Text(
                text = mail.title,
                fontSize = 20.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.SemiBold,
                color = pal.ink,
            )

            Spacer(Modifier.height(10.dp))

            // 元信息
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = mail.sender.ifEmpty { "系统" },
                    fontSize = 12.sp,
                    color = pal.inkSoft,
                )
                Text("  ·  ", fontSize = 12.sp, color = pal.inkSoft)
                Text(
                    text = formatFullTime(mail.createdAt),
                    fontSize = 12.sp,
                    color = pal.inkSoft,
                )
                if (mail.emailed) {
                    Text("  ·  ", fontSize = 12.sp, color = pal.inkSoft)
                    Text("已发至邮箱", fontSize = 12.sp, color = pal.inkSoft)
                }
            }

            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
            Spacer(Modifier.height(20.dp))

            // 正文（可选中复制）
            SelectionContainer {
                Text(
                    text = mail.body,
                    fontSize = 14.5.sp,
                    lineHeight = 24.sp,
                    color = pal.ink.copy(alpha = 0.88f),
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这封邮件？", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text("删除后不可恢复。", fontSize = 13.5.sp, color = pal.inkSoft) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = pal.accent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) {
                    Text("取消", color = pal.inkSoft)
                }
            },
        )
    }
}

// ---------------- 状态块 ----------------

@Composable
private fun LoadingBox() {
    val pal = LocalPalette.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            color = pal.accent,
            strokeWidth = 2.4.dp,
        )
    }
}

@Composable
private fun EmptyBox(
    title: String,
    desc: String,
    action: String = "",
    onAction: (() -> Unit)? = null,
) {
    val pal = LocalPalette.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            TabGlyph(kind = "mail", color = pal.inkSoft.copy(alpha = 0.3f), size = 40.dp)
            Spacer(Modifier.height(14.dp))
            Text(title, fontSize = 14.sp, color = pal.ink, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                desc,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = pal.inkSoft,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (action.isNotEmpty() && onAction != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    action,
                    fontSize = 13.sp,
                    color = pal.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onAction)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// ---------------- 时间格式化 ----------------

private fun formatTime(ts: Int): String {
    if (ts <= 0) return ""
    val now = System.currentTimeMillis()
    val t = ts * 1000L
    val diff = now - t

    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(t))
    }
}

private fun formatFullTime(ts: Int): String {
    if (ts <= 0) return ""
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts * 1000L))
}
