package sy.great.toolbox.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 更新/公告弹窗内容。
 *
 * 本文件整体搬自 KernelSU：
 *   manager/.../ui/component/dialog/DialogMiuix.kt 的 ConfirmDialogMiuix
 *
 * 保留的原始结构（一个字符没改）：
 *   · WindowDialog(show, modifier = windowInsetsPadding(systemBars.only(Top)),
 *                  title, onDismissRequest, content = { Layout(...) })
 *   · Layout 的双子节测量：内容取剩余高度，按钮固定在底部
 *   · Row(Arrangement.SpaceBetween) + 两个 TextButton(weight(1f)) + Spacer(20.dp)
 *   · 确认按钮 ButtonDefaults.textButtonColorsPrimary()
 *   · LocalDismissState 参与关闭动画
 *
 * 唯一替换点：
 *   KernelSU 的 MarkdownContent（WebView + okhttp + RegistryUrls）
 *   → 本项目的 MarkdownWebView（同样 CommonMark + WebView，但无网络依赖）
 */
@Composable
fun ConfirmDialogMiuix(
    title: String,
    content: String,
    confirmText: String,
    dismissText: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    showDialog: MutableState<Boolean>,
) {
    WindowDialog(
        show = showDialog.value,
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)),
        title = title,
        onDismissRequest = {
            onDismiss()
            showDialog.value = false
        },
        content = {
            // ---- 以下整段与 KernelSU ConfirmDialogMiuix 完全一致 ----
            Layout(
                content = {
                    val dismissState = LocalDismissState.current

                    // 内容区（KernelSU 在这里调 MarkdownContent）
                    MarkdownWebView(
                        markdown = content,
                        modifier = Modifier,
                    )

                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        TextButton(
                            text = dismissText ?: "取消",
                            onClick = {
                                onDismiss()
                                dismissState?.invoke()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = confirmText,
                            onClick = {
                                onConfirm()
                                dismissState?.invoke()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            ) { measurables, constraints ->
                if (measurables.size != 2) {
                    val button = measurables[0].measure(constraints)
                    layout(constraints.maxWidth, button.height) {
                        button.place(0, 0)
                    }
                } else {
                    val button = measurables[1].measure(constraints)
                    val lazyList = measurables[0].measure(constraints.copy(maxHeight = constraints.maxHeight - button.height))
                    layout(constraints.maxWidth, lazyList.height + button.height) {
                        lazyList.place(0, 0)
                        button.place(0, lazyList.height)
                    }
                }
            }
        }
    )
}
