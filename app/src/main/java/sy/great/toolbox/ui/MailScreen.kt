package sy.great.toolbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.blur.layerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sy.great.toolbox.tools.MailApi
import sy.great.toolbox.tools.SyncCenter
import sy.great.toolbox.ui.pressable
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.LocalPalette
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 邮箱（站内信）—— 企业级样式。
 *
 * 设计语言：
 *   · 大标题 + 分组列表，类似 Gmail / Outlook 的密度
 *   · 未读：左侧 3dp 主色竖条 + 标题加粗 + 顶部徽标
 *   · 头像色块：根据发件人名字哈希出稳定颜色，一眼区分来源
 *   · 日期分组：今天 / 昨天 / 本周 / 更早
 *   · 搜索：本地实时过滤（标题 + 正文 + 发件人）
 *   · 筛选：全部 / 未读
 *   · 详情：信封式信息卡 + 可选正文 + 底部操作栏
 *
 * 色彩全部走 LocalPalette，深色模式自动适配。
 */
@Composable
fun MailScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()

    // 先读缓存（返回页面时立即可用，不闪烁）
    val cached = sy.great.toolbox.tools.PageCache.get<List<MailApi.Mail>>("mail")

    var loading by remember { mutableStateOf(cached == null) }
    var error by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(cached ?: emptyList()) }
    var unread by remember { mutableStateOf(cached?.count { !it.read } ?: 0) }
    var opened by remember { mutableStateOf<MailApi.Mail?>(null) }

    // 滚动位置（返回时恢复）
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = sy.great.toolbox.tools.PageCache.scroll("mail"),
    )

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            sy.great.toolbox.tools.PageCache.saveScroll("mail", listState.firstVisibleItemIndex)
        }
    }

    // 搜索 / 筛选
    var query by remember { mutableStateOf("") }
    var onlyUnread by remember { mutableStateOf(false) }

    // 公告弹窗
    var showAnn by remember { mutableStateOf(false) }
    val ann = SyncCenter.currentAnnouncement

    suspend fun reload() {
        val r = withContext(Dispatchers.IO) { MailApi.list(ctx, 200) }
        loading = false
        if (!r.ok) {
            error = r.error
            return
        }
        error = ""
        items = r.items
        unread = r.unread
        sy.great.toolbox.tools.PageCache.put("mail", r.items)
    }

    // 有缓存且 30 秒内不重复拉
    LaunchedEffect(Unit) {
        if (!sy.great.toolbox.tools.PageCache.isFresh("mail", 30_000L)) {
            reload()
        }
    }

    // ---- 详情页 ----
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
            onToggleRead = { markRead ->
                scope.launch {
                    withContext(Dispatchers.IO) { MailApi.markRead(ctx, detail.id) }
                    opened = detail.copy(read = true)
                    if (markRead) reload()
                }
            },
        )
        return
    }

    // ---- 过滤后的列表 ----
    val filtered = items.filter { m ->
        (!onlyUnread || !m.read) &&
            (query.isBlank() ||
                m.title.contains(query, true) ||
                m.body.contains(query, true) ||
                m.sender.contains(query, true))
    }

    // 公告弹窗（复用全局组件）
    if (showAnn && ann != null) {
        val annState = remember(ann.id) { mutableStateOf(true) }
        sy.great.toolbox.ui.component.AnnouncementDialog(
            show = annState,
            announcement = ann,
            onDismiss = {
                showAnn = false
                sy.great.toolbox.ui.component.AnnouncementStore.markSeen(ctx, ann)
            },
        )
    }

    // ---- 液态玻璃采样层 ----
    //
    // 用 miuix-blur（与底栏 FloatingBottomBar 同一套 API），
    // 不自己碰 GraphicsLayer，不会出现 RenderNode 递归嵌套。
    val listBackdrop = top.yukonga.miuix.kmp.blur.rememberLayerBackdrop()

    Column(Modifier.fillMaxSize()) {
        MailHeader(
            unread = unread,
            total = items.size,
            onBack = onBack,
            hasAnnouncement = ann != null,
            onOpenAnnouncement = { showAnn = true },
            onMarkAllRead = if (unread > 0) {
                {
                    scope.launch {
                        withContext(Dispatchers.IO) { MailApi.markAllRead(ctx) }
                        reload()
                    }
                }
            } else null,
            onRefresh = {
                loading = true
                scope.launch { reload() }
            },
        )

        // 搜索 + 筛选（仅在有数据时显示）
        if (items.isNotEmpty()) {
            // 保留搜索能力，但样式跟随浅色主题（不挡玻璃）
            SearchAndFilter(
                query = query,
                onQueryChange = { query = it },
                onlyUnread = onlyUnread,
                onToggleUnread = { onlyUnread = !onlyUnread },
                unread = unread,
            )
        }

        when {
            loading && items.isEmpty() -> LoadingBox()

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
                desc = "系统通知和重要消息会出现在这里",
                action = "刷新",
                onAction = {
                    loading = true
                    scope.launch { reload() }
                },
            )

            filtered.isEmpty() -> EmptyBox(
                title = if (onlyUnread) "没有未读邮件" else "没有匹配的邮件",
                desc = if (onlyUnread) "所有邮件都已读" else "换个关键词试试",
                action = "清除筛选",
                onAction = {
                    query = ""
                    onlyUnread = false
                },
            )

            else -> {
                // ---- 结构与 demo 的 LazyScrollContainerContent 完全一致 ----
                //
                // demo 的 BackdropDemoScaffold 是：
                //   Box {
                //       Image(Modifier.layerBackdrop(backdrop).fillMaxSize())   ← 背景层
                //       content(backdrop)                                        ← 卡片列表（兄弟）
                //   }
                //
                // 关键：背景与列表是【兄弟节点】，不是父子。
                // 之前写成 Box.layerBackdrop { LazyColumn { 卡片 } }，
                // 卡片自己被包在采样层内部，玻璃永远采不到下层画面。
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    // 采样层：就用原来的纸色背景，不加任何渐变
                    Box(
                        Modifier
                            .fillMaxSize()
                            .layerBackdrop(listBackdrop)
                            .background(pal.paper)
                    )

                    // 内容层：与背景同层级，卡片用 drawBackdrop 采样上面的背景
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // 与 demo 的 LazyScrollContainerContent 一致：
                        // contentPadding = 16dp，间距 = 16dp
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                    items(filtered, key = { it.id }) { m ->
                    sy.great.toolbox.ui.component.GlassMailCard(
                        mail = m,
                        backdrop = listBackdrop,
                        onClick = {
                            if (!m.read) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { MailApi.markRead(ctx, m.id) }
                                    reload()
                                }
                            }
                            opened = m.copy(read = true)
                        },
                    )
                }
                item { Spacer(Modifier.height(40.dp)) }
                    }
                }
            }
        }
    }
}

// ============================================================
// 顶部标题区
// ============================================================

@Composable
private fun MailHeader(
    unread: Int,
    total: Int,
    onBack: () -> Unit,
    onMarkAllRead: (() -> Unit)?,
    onRefresh: () -> Unit,
    hasAnnouncement: Boolean = false,
    onOpenAnnouncement: () -> Unit = {},
) {
    val pal = LocalPalette.current

    // 注意：这里不能画不透明底色，否则顶部会是一块白色，
    // 与下方玻璃区域形成断层。保持透明，让页面整体背景透上来。
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 公告按钮（放在返回键左边）
            if (hasAnnouncement) {
                IconButton(onClick = onOpenAnnouncement, modifier = Modifier.size(38.dp)) {
                    androidx.compose.foundation.Canvas(Modifier.size(19.dp)) {
                        val c = pal.accent
                        val s = size.minDimension
                        val w = s * 0.10f
                        val p = s * 0.16f
                        val midX = s / 2f

                        // 钒铛轮廓：顶部圆点 + 梯形铛身
                        val x1 = p + s * 0.18f
                        val x2 = s - p - s * 0.18f
                        val bodyTop = p + s * 0.26f
                        val bodyBottom = p + s * 0.60f

                        val bell = androidx.compose.ui.graphics.Path().apply {
                            moveTo(x1, bodyTop)
                            lineTo(midX, p)
                            lineTo(x2, bodyTop)
                            lineTo(x2, bodyBottom)
                            lineTo(x1, bodyBottom)
                            close()
                        }
                        drawPath(
                            bell, c,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = w,
                                join = androidx.compose.ui.graphics.StrokeJoin.Round,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            ),
                        )

                        // 底部横线（铃铛下沿）
                        drawLine(
                            c,
                            androidx.compose.ui.geometry.Offset(x1 - w * 0.8f, bodyBottom),
                            androidx.compose.ui.geometry.Offset(x2 + w * 0.8f, bodyBottom),
                            strokeWidth = w,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        )

                        // 铃铛舌
                        drawCircle(
                            c,
                            radius = w * 1.2f,
                            center = androidx.compose.ui.geometry.Offset(midX, bodyBottom + s * 0.10f),
                        )
                    }
                }
            }

            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = pal.ink,
                    modifier = Modifier.size(21.dp),
                )
            }

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "邮箱",
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold,
                        color = pal.ink,
                        letterSpacing = (-0.3).sp,
                    )
                    if (unread > 0) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(pal.accent)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                if (unread > 99) "99+" else unread.toString(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    if (total == 0) "暂无邮件"
                    else if (unread > 0) "共 $total 封 · $unread 封未读"
                    else "共 $total 封 · 全部已读",
                    fontSize = 12.sp,
                    color = pal.inkSoft,
                )
            }

            IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp)) {
                Icon(
                    androidx.compose.material.icons.Icons.Filled.Refresh,
                    contentDescription = "刷新",
                    tint = pal.inkSoft,
                    modifier = Modifier.size(19.dp),
                )
            }
        }

        // 全部已读按钮
        if (onMarkAllRead != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(pal.paperSoft)
                        .clickable(onClick = onMarkAllRead)
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                ) {
                    Text(
                        "全部标为已读",
                        fontSize = 12.5.sp,
                        color = pal.inkSoft,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
    }
}

// ============================================================
// 搜索 + 筛选
// ============================================================

@Composable
private fun SearchAndFilter(
    query: String,
    onQueryChange: (String) -> Unit,
    onlyUnread: Boolean,
    onToggleUnread: () -> Unit,
    unread: Int,
) {
    val pal = LocalPalette.current

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // 搜索框
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(pal.paperSoft),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = pal.inkSoft,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))

            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "搜索邮件",
                        fontSize = 14.sp,
                        color = pal.inkSoft,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 14.sp,
                        color = pal.ink,
                    ),
                    cursorBrush = SolidColor(pal.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (query.isNotEmpty()) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    // 矢量叉号，不用 emoji/字符
                    Icon(
                        androidx.compose.material.icons.Icons.Filled.Close,
                        contentDescription = "清空",
                        tint = pal.inkSoft,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }

        Spacer(Modifier.height(10.dp))

        // 筛选胶囊
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                label = "全部",
                selected = !onlyUnread,
                onClick = { if (onlyUnread) onToggleUnread() },
            )
            FilterChip(
                label = if (unread > 0) "未读 $unread" else "未读",
                selected = onlyUnread,
                onClick = { if (!onlyUnread) onToggleUnread() },
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) pal.accent else pal.paperSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else pal.inkSoft,
        )
    }
}

// ============================================================
// 列表
// ============================================================

@Composable
private fun MailList(
    items: List<MailApi.Mail>,
    onOpen: (MailApi.Mail) -> Unit,
) {
    val pal = LocalPalette.current

    // 按日期分组
    val grouped = remember(items) {
        val map = LinkedHashMap<String, MutableList<MailApi.Mail>>()
        items.forEach { m ->
            val key = dateGroupLabel(m.createdAt)
            map.getOrPut(key) { ArrayList() }.add(m)
        }
        map
    }

    LazyColumn(Modifier.fillMaxSize()) {
        grouped.forEach { (label, list) ->
            item(key = "h_$label") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = pal.inkSoft,
                        letterSpacing = 0.4.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).height(1.dp).background(pal.line.copy(alpha = 0.5f)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${list.size}",
                        fontSize = 11.sp,
                        color = pal.inkSoft.copy(alpha = 0.7f),
                    )
                }
            }

            items(list, key = { it.id }) { m ->
                MailRow(mail = m, onClick = { onOpen(m) })
            }
        }

        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun MailRow(mail: MailApi.Mail, onClick: () -> Unit) {
    val pal = LocalPalette.current

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(pal.paper)
            .padding(start = 0.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        // 左侧未读竖条。
        // 注意：branches 必须显式声明为 Color，
        // 否则 if/else + when 嵌套时 Kotlin 会推断成 Any，
        // 导致 Modifier.background(color: Color) 无法匹配。
        val barColor: Color = if (mail.read) {
            Color.Transparent
        } else {
            when (mail.level) {
                "warning" -> Bad
                else -> pal.accent
            }
        }
        Box(
            Modifier
                .width(3.dp)
                .height(if (mail.read) 0.dp else 46.dp)
                .background(barColor),
        )

        Spacer(Modifier.width(if (mail.read) 3.dp else 13.dp))

        // 发件人头像
        SenderAvatar(mail.sender, mail.level, mail.read)

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            // 第一行：发件人 + 时间
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = mail.sender.ifBlank { "系统" },
                    fontSize = 13.sp,
                    fontWeight = if (mail.read) FontWeight.Normal else FontWeight.SemiBold,
                    color = if (mail.read) pal.inkSoft else pal.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatTime(mail.createdAt),
                    fontSize = 11.5.sp,
                    color = if (!mail.read) pal.accent else pal.inkSoft.copy(alpha = 0.8f),
                    fontWeight = if (!mail.read) FontWeight.Medium else FontWeight.Normal,
                )
            }

            Spacer(Modifier.height(3.dp))

            // 第二行：标题
            Text(
                text = mail.title,
                fontSize = 14.5.sp,
                fontWeight = if (mail.read) FontWeight.Normal else FontWeight.SemiBold,
                color = pal.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(3.dp))

            // 第三行：摘要
            Text(
                text = mail.body.replace(Regex("\\s+"), " ").trim(),
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = pal.inkSoft.copy(alpha = if (mail.read) 0.75f else 0.9f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // 标签行
            if (mail.level != "normal" || mail.emailed) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (mail.level == "warning") {
                        MiniTag("重要提醒", Bad)
                    } else if (mail.level == "important") {
                        MiniTag("须知", pal.accent)
                    }
                    if (mail.emailed) {
                        MiniTag("已发邮箱", pal.inkSoft)
                    }
                }
            }
        }
    }

    HorizontalDivider(
        modifier = Modifier.padding(start = 68.dp, end = 16.dp),
        thickness = 1.dp,
        color = pal.line.copy(alpha = 0.45f),
    )
}

@Composable
private fun MiniTag(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text,
            fontSize = 10.sp,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 发件人头像：首字 + 根据名字哈希的稳定渐变色块。
 */
@Composable
private fun SenderAvatar(sender: String, level: String, read: Boolean) {
    val name = sender.ifBlank { "系统" }
    val initial = name.take(1).uppercase()

    // 稳定哈希：同名总是同色
    val palette = listOf(
        Color(0xFF5B8DEF), Color(0xFF6FBF8E), Color(0xFFE0A458),
        Color(0xFFC779D0), Color(0xFF4DB6AC), Color(0xFFEF7C8E),
    )
    val idx = (name.hashCode() and 0x7fffffff) % palette.size
    val base = when (level) {
        "warning" -> Bad
        "important" -> Color(0xFFD99A3C)
        else -> palette[idx]
    }

    // 显式声明 Brush，避免 if/else 两支类型不同（Color vs Brush）导致推断失败
    val bgBrush: Brush = if (read) {
        SolidColor(base.copy(alpha = 0.14f))
    } else {
        Brush.linearGradient(listOf(base.copy(alpha = 0.9f), base.copy(alpha = 0.65f)))
    }

    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(bgBrush),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (read) base else Color.White,
        )
    }
}

// ============================================================
// 详情页
// ============================================================

@Composable
private fun MailDetail(
    mail: MailApi.Mail,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onToggleRead: (Boolean) -> Unit,
    onClaimSuccess: (Int) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pal = LocalPalette.current
    var confirmDelete by remember { mutableStateOf(false) }

    // 领取状态（本页内即时更新，不用重新拉列表）
    var claimed by remember(mail.id) { mutableStateOf(mail.claimed) }
    var claiming by remember(mail.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(pal.paper)) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
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
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmDelete = true }) {
                Text("删除", fontSize = 13.sp, color = Bad)
            }
        }

        HorizontalDivider(thickness = 1.dp, color = pal.line.copy(alpha = 0.5f))

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            // 优先级提示
            if (mail.level != "normal") {
                val (label, c) = if (mail.level == "warning")
                    "重要提醒" to Bad else "须知" to Color(0xFFD99A3C)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(c.copy(alpha = 0.10f))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                ) {
                    Text(label, fontSize = 11.sp, color = c, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(14.dp))
            }

            // 标题
            Text(
                text = mail.title,
                fontSize = 21.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.Bold,
                color = pal.ink,
                letterSpacing = (-0.2).sp,
            )

            Spacer(Modifier.height(16.dp))

            // 发件人卡片
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(pal.paperSoft)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SenderAvatar(mail.sender, mail.level, false)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        mail.sender.ifBlank { "系统" },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = pal.ink,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        formatFullTime(mail.createdAt),
                        fontSize = 11.5.sp,
                        color = pal.inkSoft,
                    )
                }
                if (mail.emailed) {
                    MiniTag("已发邮箱", pal.inkSoft)
                }
            }

            Spacer(Modifier.height(18.dp))

            // ---- 奖励领取卡 ----
            if (mail.hasReward) {
                RewardClaimCardInline(
                    mail = mail,
                    claimed = claimed,
                    claiming = claiming,
                    onClaim = {
                        if (claiming || claimed) return@RewardClaimCardInline
                        claiming = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { MailApi.claim(ctx, mail.id) }
                            claiming = false

                            if (r.ok) {
                                claimed = true
                                onClaimSuccess(mail.id)

                                val tip = buildString {
                                    append("领取成功")
                                    if (r.points > 0) append(" +" + r.points + " 积分")
                                    if (r.exp > 0) append(" +" + r.exp + " 经验")
                                    if (r.levelUp) append("　升到 Lv." + r.level)
                                }
                                android.widget.Toast.makeText(ctx, tip, android.widget.Toast.LENGTH_SHORT).show()

                                // 同步 SyncCenter（主页积分立刻变）
                                SyncCenter.syncNow(ctx)
                            } else {
                                android.widget.Toast.makeText(
                                    ctx,
                                    r.error.ifBlank { "领取失败" },
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                )
                Spacer(Modifier.height(18.dp))
            }

            // 正文
            SelectionContainer {
                Text(
                    text = mail.body,
                    fontSize = 15.sp,
                    lineHeight = 26.sp,
                    color = pal.ink.copy(alpha = 0.9f),
                )
            }

            Spacer(Modifier.height(40.dp))

            // 底部信息
            HorizontalDivider(thickness = 1.dp, color = pal.line.copy(alpha = 0.4f))
            Spacer(Modifier.height(14.dp))
            Text(
                "本邮件由系统发送，请勿直接回复。",
                fontSize = 11.5.sp,
                color = pal.inkSoft.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(30.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text("删除这封邮件？", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Text("删除后不可恢复。", fontSize = 13.5.sp, color = pal.inkSoft)
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = Bad) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("取消", color = pal.inkSoft)
                }
            },
        )
    }
}

// ============================================================
// 状态块
// ============================================================

@Composable
private fun LoadingBox() {
    val pal = LocalPalette.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                color = pal.accent,
                strokeWidth = 2.4.dp,
            )
            Spacer(Modifier.height(12.dp))
            Text("加载中...", fontSize = 12.5.sp, color = pal.inkSoft)
        }
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
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            TabGlyph(kind = "mail", color = pal.inkSoft.copy(alpha = 0.28f), size = 44.dp)
            Spacer(Modifier.height(16.dp))
            Text(title, fontSize = 15.sp, color = pal.ink, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                desc,
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                color = pal.inkSoft,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (action.isNotEmpty() && onAction != null) {
                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(pal.accent.copy(alpha = 0.10f))
                        .clickable(onClick = onAction)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text(
                        action,
                        fontSize = 13.sp,
                        color = pal.accent,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

// ============================================================
// 时间工具
// ============================================================

private fun dateGroupLabel(ts: Int): String {
    if (ts <= 0) return "更早"
    val now = Calendar.getInstance()
    val t = Calendar.getInstance().apply { timeInMillis = ts * 1000L }

    val sameYear = now.get(Calendar.YEAR) == t.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - t.get(Calendar.DAY_OF_YEAR)

    return when {
        sameYear && dayDiff == 0 -> "今天"
        sameYear && dayDiff == 1 -> "昨天"
        sameYear && dayDiff in 2..7 -> "本周"
        sameYear -> SimpleDateFormat("M 月 d 日", Locale.getDefault()).format(Date(ts * 1000L))
        else -> SimpleDateFormat("yyyy 年 M 月 d 日", Locale.getDefault()).format(Date(ts * 1000L))
    }
}

private fun formatTime(ts: Int): String {
    if (ts <= 0) return ""
    val now = System.currentTimeMillis()
    val t = ts * 1000L
    val diff = now - t

    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
        diff < 7 * 86_400_000L -> SimpleDateFormat("M-d", Locale.getDefault()).format(Date(t))
        else -> SimpleDateFormat("M-d", Locale.getDefault()).format(Date(t))
    }
}

private fun formatFullTime(ts: Int): String {
    if (ts <= 0) return ""
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts * 1000L))
}

/**
 * 邮件奖励领取卡（详情页内）。
 *
 * 未领取：主色按钮，点击领取。
 * 已领取：置灰 + 「已领取」文字。
 */
@Composable
private fun RewardClaimCardInline(
    mail: MailApi.Mail,
    claimed: Boolean,
    claiming: Boolean,
    onClaim: () -> Unit,
) {
    val pal = LocalPalette.current

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(pal.accent.copy(alpha = 0.07f))
            .padding(16.dp),
    ) {
        Text(
            "内含奖励",
            fontSize = 12.sp,
            color = pal.inkSoft,
        )

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (mail.rewardPoints > 0) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "+" + mail.rewardPoints,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = pal.accent,
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "积分",
                            fontSize = 12.sp,
                            color = pal.inkSoft,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                }
                if (mail.rewardExp > 0) {
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "+" + mail.rewardExp,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = pal.ink,
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "经验",
                            fontSize = 11.5.sp,
                            color = pal.inkSoft,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
            }

            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            claimed -> pal.paperSoft
                            claiming -> pal.accent.copy(alpha = 0.5f)
                            else -> pal.accent
                        }
                    )
                    .pressable(
                        enabled = !claimed && !claiming,
                        pressedScale = 0.95f,
                        onClick = onClaim,
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(
                    when {
                        claimed -> "已领取"
                        claiming -> "领取中..."
                        else -> "领取"
                    },
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (claimed) pal.inkSoft else Color.White,
                )
            }
        }
    }
}
