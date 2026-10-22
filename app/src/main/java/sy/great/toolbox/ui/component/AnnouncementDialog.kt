package sy.great.toolbox.ui.component

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.tools.VersionInfo
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 公告弹窗。
 *
 * 来源：version.json 的 announcement 字段。
 *
 * 展示规则：
 *   · enabled=false 不弹
 *   · showOnce=true 时，同一个 id 只展一次（记在 SharedPreferences）
 *   · showOnce=false 则每次启动都弹
 */
object AnnouncementStore {

    private const val PREFS = "csdemo_announcement"
    private const val K_SEEN = "seen_id"

    /** 是否应该展示 */
    fun shouldShow(ctx: Context, a: VersionInfo.Announcement): Boolean {
        if (!a.enabled) return false
        if (a.title.isBlank() && a.content.isBlank()) return false
        if (!a.showOnce) return true
        val seen = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_SEEN, "") ?: ""
        return seen != a.id
    }

    /** 标记已读 */
    fun markSeen(ctx: Context, a: VersionInfo.Announcement) {
        if (a.id.isBlank()) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_SEEN, a.id)
            .apply()
    }
}

/**
 * 公告弹窗 UI（KernelSU 同款 ExpressiveDialog）。
 */
/**
 * 公告弹窗。
 *
 * 与 UpdateDialog 完全同一套结构和视觉：
 *   OverlayDialog + 居中标题 + 可滚动内容 + 底部等宽按钮。
 *
 * 与旧版的区别：
 *   旧版用 ExpressiveDialog（Material3 AlertDialog 包装），
 *   视觉上还是 Material 的圆角小弹窗，与 KernelSU 不一致。
 *   现在统一成 miuix OverlayDialog，和模块更新弹窗、确认弹窗都一样。
 */
@Composable
fun AnnouncementDialog(
    show: Boolean,
    announcement: VersionInfo.Announcement,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current

    // 把正文里的空行保留，MarkdownText 会转成段落间距
    val markdown = announcement.content.ifBlank { "（暂无内容）" }

    OverlayDialog(
        show = show,
        onDismissRequest = {
            AnnouncementStore.markSeen(ctx, announcement)
            onDismiss()
        },
        insideMargin = DpSize(0.dp, 0.dp),
        content = {
            // 标题：居中，与更新弹窗一致
            Text(
                text = announcement.title.ifBlank { "公告" },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 12.dp),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )

            // 正文：WebView 必须给固定高度，否则量成 0
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                MarkdownWebView(
                    markdown = markdown,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            // 按钮：通栏，与更新弹窗一致
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            ) {
                TextButton(
                    text = "知道了",
                    onClick = {
                        AnnouncementStore.markSeen(ctx, announcement)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )
}
