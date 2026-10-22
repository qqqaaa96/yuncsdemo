package sy.great.toolbox.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 版本更新弹窗。
 *
 * 结构直接搬自 KernelSU：
 *   me/weishu/kernelsu/ui/component/dialog/DialogMiuix.kt 的 ConfirmDialogMiuix
 *   me/weishu/kernelsu/ui/component/uninstalldialog/UninstallDialogMiuix.kt
 *
 * 两边 miuix 版本都是 0.9.4，所以 OverlayDialog 参数完全一致：
 *   OverlayDialog(
 *       show = ...,
 *       onDismissRequest = ...,
 *       insideMargin = DpSize(...),
 *       content = { ... },
 *   )
 *
 * KernelSU 的写法要点（原样保留）：
 *   · 标题不传参数，自己在 content 里画，居中对齐
 *   · 按钮行放在 content 内部，两个 TextButton 各 weight(1f) 铺满
 *   · 确认按钮用 ButtonDefaults.textButtonColorsPrimary()（主色蓝）
 *
 * 唯一替换点：
 *   KernelSU 用 MarkdownContent（WebView + commonmark）渲染日志，
 *   本项目改用已有的 MarkdownText（纯 Compose，无额外依赖）。
 */
@Composable
fun UpdateDialog(
    versionName: String,
    changelog: List<String>,
    forceUpdate: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 拼 Markdown：
    //   # 版本号  → 大号标题
    //   ---      → 分割线
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

    OverlayDialog(
        show = true,
        onDismissRequest = { if (!forceUpdate) onDismiss() },
        insideMargin = DpSize(0.dp, 0.dp),
        content = {
            // ---- 标题（KernelSU 同款：居中、title4 字号、Medium）----
            Text(
                text = "更新日志",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 12.dp),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )

            // ---- 日志正文 ----
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                MarkdownText(markdown)

                if (forceUpdate) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "此版本为必需的更新，请完成更新后继续使用。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 按钮行（KernelSU 同款：两个 weight(1f) + 20dp 间隔）----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                if (!forceUpdate) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                }
                TextButton(
                    text = "更新",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )
}
