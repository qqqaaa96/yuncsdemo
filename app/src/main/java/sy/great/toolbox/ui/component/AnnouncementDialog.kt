package sy.great.toolbox.ui.component

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalContext
import sy.great.toolbox.tools.VersionInfo

/**
 * 公告展示状态。
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
 * 公告弹窗。
 *
 * 外壳与更新弹窗完全相同：都走 KernelSU 的 ConfirmDialogMiuix。
 * 只传一个按钮（"知道了"），所以 dismissText = null。
 */
@Composable
fun AnnouncementDialog(
    show: MutableState<Boolean>,
    announcement: VersionInfo.Announcement,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current

    val markdown = announcement.content.ifBlank { "（暂无内容）" }

    ConfirmDialogMiuix(
        title = announcement.title.ifBlank { "公告" },
        content = markdown,
        confirmText = "知道了",
        dismissText = null,   // 单按钮
        onConfirm = {
            AnnouncementStore.markSeen(ctx, announcement)
            onDismiss()
        },
        onDismiss = {
            AnnouncementStore.markSeen(ctx, announcement)
            onDismiss()
        },
        showDialog = show,
    )
}
