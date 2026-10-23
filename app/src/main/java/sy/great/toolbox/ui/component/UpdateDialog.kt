package sy.great.toolbox.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState

/**
 * 版本更新弹窗。
 *
 * 外壳完全用 KernelSU 的 ConfirmDialogMiuix（见同目录 ConfirmDialogMiuix.kt），
 * 这里只负责把 changelog 拼成 Markdown。
 */
@Composable
fun UpdateDialog(
    show: MutableState<Boolean>,
    versionName: String,
    changelog: List<String>,
    forceUpdate: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 拼 Markdown：
    //   # 版本号  → 大号标题（h1，26sp）
    //   ---      → 4px 灰粗分割线（markdown.css 里的 hr 样式）
    //   - 条目    → 圆点列表
    val markdown = buildString {
        if (versionName.isNotBlank()) {
            append("# ").append(versionName).append("\n\n")
            append("---\n\n")
        }
        if (changelog.isEmpty()) {
            append("- 本次更新包含体验优化与问题修复。")
        } else {
            changelog.forEach { append("- ").append(it).append('\n') }
        }
    }

    ConfirmDialogMiuix(
        title = "更新日志",
        content = markdown,
        confirmText = "更新",
        dismissText = if (forceUpdate) null else "取消",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        showDialog = show,
    )
}
