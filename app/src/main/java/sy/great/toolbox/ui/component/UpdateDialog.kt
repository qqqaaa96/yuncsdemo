package sy.great.toolbox.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import sy.great.toolbox.ui.theme.LocalPalette

/**
 * 版本更新弹窗。
 *
 * 容器用 Compose 标准 Dialog，而不是 miuix OverlayDialog。
 *
 * 为什么不用 OverlayDialog：
 *   OverlayDialog 需要拿 Window.decorView，而本项目主页包在
 *   HorizontalPager + layerBackdrop（模糊采样层）里，
 *   这种嵌套下 miuix 拿不到正确的窗口，表现为点击无反应。
 *   Compose 标准 Dialog 自己建窗口，不依赖宿主层级，任何位置都能弹。
 *
 * 视觉保持 KernelSU 风格：
 *   · 24dp 大圆角卡片
 *   · 居中标题
 *   · 内容区 WebView 渲染 Markdown
 *   · 底部左右等宽胶囊按钮，确认按钮为主色
 */
@Composable
fun UpdateDialog(
    show: Boolean,
    versionName: String,
    changelog: List<String>,
    forceUpdate: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return

    val pal = LocalPalette.current

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

    Dialog(
        onDismissRequest = { if (!forceUpdate) onDismiss() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(pal.paper, RoundedCornerShape(24.dp))
                .padding(top = 24.dp, bottom = 16.dp),
        ) {
            // ---- 标题 ----
            Text(
                text = "更新日志",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = pal.ink,
                textAlign = TextAlign.Center,
            )

            // ---- 日志正文 ----
            // WebView 必须给固定高度，否则在无界约束下会量成 0。
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

                if (forceUpdate) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "此版本为必需的更新，请完成更新后继续使用。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = pal.accent,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 按钮行：左右等宽 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                if (!forceUpdate) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("取消", fontSize = 15.sp, color = pal.inkSoft)
                    }
                    Spacer(Modifier.width(20.dp))
                }
                TextButton(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        "更新",
                        fontSize = 15.sp,
                        color = pal.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
