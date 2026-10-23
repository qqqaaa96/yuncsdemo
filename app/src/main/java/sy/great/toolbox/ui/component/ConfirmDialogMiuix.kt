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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 写崩溃日志。
 *
 * 路径：
 *   /sdcard/Android/data/sy.great.toolbox/files/csdemo_crash.log
 *
 * 为什么用这个路径：
 *   不需要任何存储权限（App 私有目录），
 *   MT 管理器能直接打开，不用装日志工具。
 */
internal fun crashLog(ctx: android.content.Context?, tag: String, t: Throwable) {
    try {
        val base = ctx ?: return
        val dir = base.getExternalFilesDir(null) ?: base.filesDir
        val f = java.io.File(dir, "csdemo_crash.log")
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        f.appendText(
            "\n===== ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} [$tag] =====\n" +
                    sw.toString() + "\n"
        )
    } catch (_: Throwable) {
        // 连日志都写不了就算了
    }
}

/**
 * 与 KernelSU 完全一致的确认弹窗。
 *
 * 源码位置：
 *   KernelSU/manager/app/src/main/java/me/weishu/kernelsu/ui/component/dialog/DialogMiuix.kt
 *   函数：ConfirmDialogMiuix
 *
 * 本文件是那个函数的逐行拷贝，只做了两处不得不改的替换：
 *
 *   1. 参数 visuals: ConfirmDialogVisuals → 拆成 title/content/confirm/dismiss 四个普通参数
 *      （ConfirmDialogVisuals 是 KernelSU 的内部接口，本项目没有）
 *
 *   2. MarkdownContent（KernelSU 的 WebView 渲染器）→ MarkdownWebView
 *      （本项目同名实现，同为 CommonMark + WebView）
 *
 * WindowDialog / Layout 双子节测量 / LocalDismissState /
 * Arrangement.SpaceBetween / ButtonDefaults.textButtonColorsPrimary()
 * 全部原样保留。
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
            Layout(
                content = {
                    val dismissState = LocalDismissState.current

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
