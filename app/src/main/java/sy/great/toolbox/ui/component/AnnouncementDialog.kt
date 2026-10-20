package sy.great.toolbox.ui.component

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.tools.VersionInfo

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
@Composable
fun AnnouncementDialog(
    announcement: VersionInfo.Announcement,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current

    ExpressiveDialog(
        onDismissRequest = {
            AnnouncementStore.markSeen(ctx, announcement)
            onDismiss()
        },
        title = {
            Text(
                announcement.title.ifBlank { "公告" },
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                Modifier
                    .height(260.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    announcement.content,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                )
                Spacer(Modifier.height(8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AnnouncementStore.markSeen(ctx, announcement)
                onDismiss()
            }) {
                Text("知道了")
            }
        }
    )
}
