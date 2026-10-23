package sy.great.toolbox.ui.component

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalContext
import sy.great.toolbox.tools.VersionInfo

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
