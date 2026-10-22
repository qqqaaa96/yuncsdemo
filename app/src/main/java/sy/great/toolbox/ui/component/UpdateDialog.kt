package sy.great.toolbox.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.ui.theme.LocalPalette

/**
 * 版本更新弹窗。
 *
 * 布局参照 KernelSU 的更新确认框：
 *   · 标题：「更新日志」
 *   · 正文：版本号 + 更新条目列表（逐条带项目符号）
 *   · 按钮：「取消」/「更新」
 *
 * 与公告弹窗共用 ExpressiveDialog，保证视觉一致。
 *
 * @param versionName  新版本号，如 "1.0.3"
 * @param changelog    更新条目
 * @param forceUpdate  强制更新时不允许取消
 * @param onConfirm    点击「更新」
 */
@Composable
fun UpdateDialog(
    versionName: String,
    changelog: List<String>,
    forceUpdate: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val pal = LocalPalette.current

    ExpressiveDialog(
        onDismissRequest = { if (!forceUpdate) onDismiss() },
        title = {
            Text(
                "发现新版本",
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 版本号
                Text(
                    text = if (versionName.isNotBlank()) "版本 $versionName" else "版本更新",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = pal.accent,
                )

                Spacer(Modifier.height(14.dp))

                if (changelog.isEmpty()) {
                    Text(
                        "本次更新包含体验优化与问题修复。",
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        color = pal.ink.copy(alpha = 0.85f),
                    )
                } else {
                    changelog.forEach { line ->
                        Text(
                            text = "· $line",
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            color = pal.ink.copy(alpha = 0.85f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(7.dp))
                    }
                }

                if (forceUpdate) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "此版本为必需的更新，请完成更新后继续使用。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = pal.accent,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("更新", color = pal.accent, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = if (!forceUpdate) {
            {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = pal.inkSoft)
                }
            }
        } else null,
    )
}
