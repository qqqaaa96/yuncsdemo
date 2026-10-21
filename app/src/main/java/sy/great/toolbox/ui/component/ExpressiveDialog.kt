package sy.great.toolbox.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties

/**
 * KernelSU 同款弹窗。
 *
 * 复刻自 KernelSU-main/manager/.../ui/component/material/ExpressiveDialog.kt：
 *   · 圆角用 MaterialTheme.shapes.extraLarge（比默认 AlertDialog 更圆）
 *   · 颜色、高度全部走 AlertDialogDefaults
 *   · 不自定义背景，跟随当前 MaterialTheme 深浅色
 *
 * 项目已有 MaterialTheme（见 ui/theme/Theme.kt），所以能直接用。
 */
@Composable
fun ExpressiveDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}

/**
 * 更新日志弹窗（KernelSU 同款）。
 *
 * 结构：
 *   标题：更新日志
 *   内容：Markdown 渲染、可滚动
 *   按钮：单个“更新”/“知道了”
 *
 * 与 KernelSU 的 UpdateCard 一致：
 *   有 changelog 就弹这个，没有就直接跳下载页。
 */
@Composable
fun ChangelogDialog(
    versionName: String,
    changelog: String,
    onDismiss: () -> Unit,
    onConfirm: (() -> Unit)? = null,
    confirmText: String = "知道了",
) {
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (versionName.isNotBlank()) "更新日志 · " + versionName else "更新日志",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            if (changelog.isBlank()) {
                Text("暂无更新说明", fontSize = 13.sp)
            } else {
                MarkdownScroll(changelog, maxHeightDp = 320)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm?.invoke()
            }) {
                Text(confirmText)
            }
        }
    )
}

/** 带确定/取消的简版弹窗（KernelSU 同款签名） */
@Composable
fun ExpressiveConfirmDialog(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    dismissText: String? = null,
) {
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = message?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText) }
        },
        dismissButton = dismissText?.let {
            { TextButton(onClick = onDismiss) { Text(it) } }
        },
    )
}
