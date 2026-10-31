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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sy.great.toolbox.tools.AvatarStore
import sy.great.toolbox.tools.PageCache
import sy.great.toolbox.tools.RankApi
import sy.great.toolbox.ui.component.FxText
import sy.great.toolbox.ui.component.TagBadge
import sy.great.toolbox.ui.theme.LocalPalette

/**
 * 排行榜页面。
 *
 * 视觉规范与邮箱列表一致：
 *   · 大标题 + 副标题（更新时间）
 *   · 前三名特殊配色（金 / 银 / 铜）
 *   · 头像优先用本地缓存，未加载时用首字母色块占位
 *   · 称号复用 TagBadge（支持炫彩流光）
 *   · 隐私受限的数据不显示（后端已脱敏，前端只是不画）
 */
@Composable
fun RankingScreen(onBack: () -> Unit) {
    val CACHE_KEY = "ranking"

    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()

    // 先读缓存，有就立即可用（避免重新加载、闪烁）
    val cached = PageCache.get<RankApi.RankResult>(CACHE_KEY)

    var loading by remember { mutableStateOf(cached == null) }
    var error by remember { mutableStateOf("") }
    var updatedAt by remember { mutableStateOf(cached?.updatedAt ?: "") }
    var items by remember { mutableStateOf(cached?.items ?: emptyList()) }

    // 列表滚动位置（返回时恢复）
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = PageCache.scroll(CACHE_KEY),
    )

    suspend fun reload() {
        val r = withContext(Dispatchers.IO) { RankApi.ranking(ctx) }
        loading = false
        if (!r.ok) {
            error = r.error
            return
        }
        error = ""
        updatedAt = r.updatedAt
        items = r.items
        // 写缓存
        PageCache.put(CACHE_KEY, r)
    }

    // 首次进入：有缓存且新鲜就跳过网络；否则后台刷新（不阻塞 UI）
    LaunchedEffect(Unit) {
        if (!PageCache.isFresh(CACHE_KEY, 60_000L)) {
            reload()
        }
    }

    // 离开页面时记住滚动位置
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            PageCache.saveScroll(CACHE_KEY, listState.firstVisibleItemIndex)
        }
    }

    Column(Modifier.fillMaxSize().background(pal.paper)) {
        // ---- 顶栏 ----
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
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
                    "排行榜",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = pal.ink,
                    letterSpacing = (-0.2).sp,
                )
                if (updatedAt.isNotBlank()) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        "更新于 $updatedAt",
                        fontSize = 10.sp,
                        color = pal.inkSoft,
                    )
                }
            }

            IconButton(
                onClick = { loading = true; scope.launch { reload() } },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "刷新",
                    tint = pal.inkSoft,
                    modifier = Modifier.size(19.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        when {
            loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = pal.accent,
                    strokeWidth = 2.4.dp,
                )
            }

            error.isNotEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, fontSize = 13.sp, color = pal.inkSoft)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "重试",
                        fontSize = 13.sp,
                        color = pal.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(pal.accent.copy(alpha = 0.1f))
                            .clickable {
                                loading = true
                                scope.launch { reload() }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TabGlyph(kind = "home", color = pal.inkSoft.copy(alpha = 0.3f), size = 42.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("暂无排行数据", fontSize = 14.sp, color = pal.ink, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "管理员配置后会出现在这里",
                        fontSize = 12.5.sp,
                        color = pal.inkSoft,
                    )
                }
            }

            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.rank }) { it ->
                    RankRow(it)
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

/** 前三名配色 */
private fun medalColor(rank: Int): Color? = when (rank) {
    1 -> Color(0xFFE6A020)   // 金
    2 -> Color(0xFF9FA8B3)   // 银
    3 -> Color(0xFFC0824B)   // 铜
    else -> null
}

@Composable
private fun RankRow(item: RankApi.RankItem) {
    val pal = LocalPalette.current
    val medal = medalColor(item.rank)

    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (item.isMe) pal.accent.copy(alpha = 0.06f) else Color.Transparent
            )
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ---- 名次 ----
        Box(
            Modifier.width(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (medal != null) {
                Box(
                    Modifier
                        .size(19.dp)
                        .clip(CircleShape)
                        .background(medal.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        item.rank.toString(),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = medal,
                    )
                }
            } else {
                Text(
                    item.rank.toString(),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = pal.inkSoft,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // ---- 头像 ----
        RankAvatar(item)

        Spacer(Modifier.width(10.dp))

        // ---- 名字 + 称号 + 连续签到 ----
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 名字：支持独立流光
                sy.great.toolbox.ui.component.FxText(
                    text = item.name.ifBlank { "未知" },
                    color = item.nameColor,
                    fxColors = item.nameFx,
                    fontSize = 12,
                    fontWeight = if (item.isMe) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )

                if (item.isMe) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "我",
                        fontSize = 8.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(pal.accent)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }

                // 称号
                val tag = item.tag
                if (tag != null && tag.text.isNotBlank()) {
                    Spacer(Modifier.width(5.dp))
                    TagBadge(tag = tag, fontSize = 8)
                }
            }

            if (item.streak > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "连续签到 ${item.streak} 天",
                    fontSize = 9.sp,
                    color = pal.inkSoft,
                )
            }
        }

        Spacer(Modifier.width(6.dp))

        // ---- 积分 ----
        if (item.points >= 0) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    item.points.toString(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (item.rank <= 3 && medal != null) medal else pal.ink,
                )
                Text(
                    "积分",
                    fontSize = 10.sp,
                    color = pal.inkSoft,
                )
            }
        } else {
            Text(
                "已隐藏",
                fontSize = 11.sp,
                color = pal.inkSoft.copy(alpha = 0.6f),
            )
        }
    }

    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp, end = 16.dp),
        thickness = 1.dp,
        color = pal.line.copy(alpha = 0.4f),
    )
}

/**
 * 排行榜头像。
 *
 * 流程（必须完整，否则只能看到自己的头像）：
 *   1. 先读本地缓存 —— 有就直接显示，瞬时出图
 *   2. 本地没有 → 后台 fetchAndCache 下载 → 写入缓存
 *   3. 下载完成 → 更新 state 触发重组 → 显示图片
 *   4. 下载失败 → 保留首字母色块
 *
 * 之前只做了第 1 步，没有第 2 步 ——
 * 本地缓存只有「自己上传头像」时才会写入，
 * 所以别人的头像永远是首字母色块。
 */
@Composable
private fun RankAvatar(item: RankApi.RankItem) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current

    // 用 state 存图：下载完成后赋值即可触发重组
    var bitmap by remember(item.uid, item.avatarAt, item.userId) {
        mutableStateOf(
            if (item.avatarAt <= 0 || item.userId <= 0) null
            else AvatarStore.loadLocal(ctx, item.userId)
        )
    }

    // 本地没有就去下载。
    // key 必须包含 avatarAt：用户换了头像时要重拉，否则永远显示旧图。
    LaunchedEffect(item.userId, item.avatarAt) {
        if (item.avatarAt <= 0 || item.userId <= 0) return@LaunchedEffect
        if (bitmap != null) return@LaunchedEffect

        val ok = withContext(Dispatchers.IO) {
            try {
                AvatarStore.fetchAndCache(ctx, item.userId, item.avatarAt)
            } catch (_: Throwable) {
                false
            }
        }
        if (ok) {
            bitmap = AvatarStore.loadLocal(ctx, item.userId)
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(29.dp).clip(CircleShape),
        )
        return
    }

    // 首字母色块
    val name = item.name.ifBlank { item.uid }
    val palette = listOf(
        Color(0xFF5B8DEF), Color(0xFF6FBF8E), Color(0xFFE0A458),
        Color(0xFFC779D0), Color(0xFF4DB6AC), Color(0xFFEF7C8E),
    )
    val idx = (name.hashCode() and 0x7fffffff) % palette.size
    val base = palette[idx]

    Box(
        Modifier
            .size(29.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(base.copy(alpha = 0.9f), base.copy(alpha = 0.62f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1).uppercase().ifBlank { "?" },
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}
