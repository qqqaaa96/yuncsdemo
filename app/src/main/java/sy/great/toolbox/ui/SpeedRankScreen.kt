package sy.great.toolbox.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sy.great.toolbox.tools.AvatarStore
import sy.great.toolbox.tools.RankApi
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Good
import sy.great.toolbox.ui.theme.LocalPalette
import sy.great.toolbox.ui.theme.Warn

/**
 * 测速排行榜。
 *
 * 展示每个用户的最好成绩：
 *   头像 + 用户名 + 网络类型（Wi-Fi / 移动数据）
 *   + 下行 / 上行 / 延迟 / 丢包率
 *
 * 排序可切：下行 / 上行 / 延迟 / 丢包。
 * 延迟和丢包是“越小越好”，所以排昇序。
 */
@Composable
fun SpeedRankScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()

    // 排序维度：固定按下行排名（已去掉顶部的切换按钮）。
    // 保留变量是为了以后需要再加快（也可改成设置项）。
    val by = "down"
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<RankApi.SpeedItem>>(emptyList()) }

    suspend fun reload(target: String) {
        loading = true
        val r = withContext(Dispatchers.IO) { RankApi.speedRanking(ctx, target) }
        loading = false
        if (!r.ok) {
            error = r.error
            return
        }
        error = ""
        items = r.items
    }

    LaunchedEffect(by) {
        reload(by)
    }

    Column(Modifier.fillMaxSize().background(pal.paper)) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            PageHeader(title = "测速排行榜", onBack = onBack)
        }

        Spacer(Modifier.height(12.dp))

        when {
            loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = pal.accent,
                    strokeWidth = 2.4.dp
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
                            .clickable { scope.launch { reload(by) } }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无测速记录", fontSize = 14.sp, color = pal.ink, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "去测一次速就能上榜了",
                        fontSize = 12.sp,
                        color = pal.inkSoft
                    )
                }
            }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.userId * 1000 + it.rank }) { item ->
                    SpeedRankRow(item = item)
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

/** 前三名配色 */
private fun medalColor(rank: Int): Color? = when (rank) {
    1 -> Color(0xFFE6A020)
    2 -> Color(0xFF9FA8B3)
    3 -> Color(0xFFC0824B)
    else -> null
}

/**
 * 单行布局（与需求一致）：
 *
 *   [名次] [头像] 用户名  Wi-Fi/流量  ↓下载/↑上传  延迟  消耗
 *
 * 空间分配：
 *   头像固定 30dp；用户名 weight(1f) 自适应并省略；
 *   右侧三组数据固定宽度，保证多行对齐（不会因数字长短而参差）。
 */
@Composable
private fun SpeedRankRow(item: RankApi.SpeedItem) {
    val pal = LocalPalette.current
    val medal = medalColor(item.rank)

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (item.isMe) pal.accent.copy(alpha = 0.06f) else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // ---- 名次 ----
            Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                if (medal != null) {
                    Box(
                        Modifier.size(18.dp).clip(CircleShape)
                            .background(medal.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            item.rank.toString(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = medal
                        )
                    }
                } else {
                    Text(
                        item.rank.toString(),
                        fontSize = 10.sp,
                        color = pal.inkSoft
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // ---- 头像 ----
            SpeedAvatar(item)

            Spacer(Modifier.width(9.dp))

            // ---- 用户名 + 网络类型胶囊 ----
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.name.ifBlank { "未知" },
                        fontSize = 12.5.sp,
                        fontWeight = if (item.isMe) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (medal != null) medal else pal.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (item.isMe) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "我",
                            fontSize = 8.sp,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(pal.accent)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                NetBadge(item.net)
            }

            Spacer(Modifier.width(8.dp))

            // ---- 下载 / 上传 ----
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(62.dp)) {
                Text(
                    "↓%.1f".format(item.downMbps),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = pal.ink,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "↑%.1f".format(item.upMbps),
                    fontSize = 9.5.sp,
                    color = pal.inkSoft,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }

            Spacer(Modifier.width(8.dp))

            // ---- 延迟 ----
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(42.dp)) {
                Text(
                    item.latencyMs.toString(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = pal.ink,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text("ms", fontSize = 9.sp, color = pal.inkSoft)
            }

            Spacer(Modifier.width(8.dp))

            // ---- 消耗流量 ----
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(56.dp)) {
                Text(
                    formatTraffic(item.bytes),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = pal.inkSoft,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text("消耗", fontSize = 9.sp, color = pal.inkSoft)
            }
        }
    }

    HorizontalDivider(
        modifier = Modifier.padding(start = 68.dp, end = 16.dp),
        thickness = 1.dp,
        color = pal.line.copy(alpha = 0.4f)
    )
}

/**
 * 流量格式化：>1GB 进位到 GB，否则 MB/KB。
 *
 * 与测速页的 formatBytes 同一套进位规则：
 * 超过 1024MB 就记 1GB，不堆成“1432.6 MB”。
 */
private fun formatTraffic(b: Long): String {
    if (b <= 0L) return "--"
    val kb = 1024.0
    val gb = b / kb / kb / kb
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        b >= 1024L * 1024 -> "%.1f MB".format(b / kb / kb)
        b >= 1024L -> "%.0f KB".format(b / kb)
        else -> b.toString() + " B"
    }
}

/** 网络类型小胶囊：Wi-Fi 绿色，移动数据橙色 */
@Composable
private fun NetBadge(net: String) {
    if (net.isBlank()) return
    val pal = LocalPalette.current
    val (fg, label) = when {
        net.startsWith("Wi-Fi") -> Good to "Wi-Fi"
        net.contains("移动") -> Warn to "流量"
        net.contains("以太") -> Good to "以太网"
        net.contains("VPN") -> pal.inkSoft to "VPN"
        else -> pal.inkSoft to net
    }
    Box(
        Modifier
            .background(fg.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(label, fontSize = 8.5.sp, color = fg, fontWeight = FontWeight.Medium)
    }
}

/**
 * 头像：先读缓存，没有就下载（与 RankingScreen 同一套逻辑）。
 *
 * 只读缓存不下载的话，只有自己上传过头像的账号能显示，
 * 其他人的永远是首字母色块。
 */
@Composable
private fun SpeedAvatar(item: RankApi.SpeedItem) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current

    var bitmap by remember(item.userId, item.avatarAt) {
        mutableStateOf(
            if (item.avatarAt <= 0 || item.userId <= 0) null
            else AvatarStore.loadLocal(ctx, item.userId)
        )
    }

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
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(30.dp).clip(CircleShape)
        )
        return
    }

    // 首字母色块
    val name = item.name.ifBlank { item.uid }
    val palette = listOf(
        Color(0xFF5B8DEF), Color(0xFF6FBF8E), Color(0xFFE0A458),
        Color(0xFFC77DD6), Color(0xFFE0705F), Color(0xFF4FB8C9)
    )
    val color = palette[(name.hashCode().let { if (it < 0) -it else it }) % palette.size]
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.take(1).uppercase(),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}
