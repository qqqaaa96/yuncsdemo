package sy.great.toolbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.LocalPalette

/**
 * NFC 功能主页。
 *
 * 布局参照 NFC Tools：顶部状态，下面四个大入口。
 *
 * 入口：
 *   · 读取标签 —— 靠卡后展示完整信息
 *   · 写入标签 —— 选类型 → 编辑 → 靠卡写入
 *   · 标签信息 —— 只看技术参数
 *   · 我的码 —— 二维码/条形码生成
 */
@Composable
fun NfcHomeScreen(
    onBack: () -> Unit,
    nfcSupported: Boolean,
    nfcEnabled: Boolean,
    onOpenRead: () -> Unit,
    onOpenWrite: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenCode: () -> Unit,
) {
    val pal = LocalPalette.current

    Column(Modifier.fillMaxSize().background(pal.paper)) {
        PageHeader("NFC", onBack)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(10.dp))

            // ---- 状态提示 ----
            if (!nfcSupported) {
                StatusTip("本设备不支持 NFC", Color(0xFFD99A3C))
                Spacer(Modifier.height(14.dp))
            } else if (!nfcEnabled) {
                StatusTip("NFC 未开启，请到系统设置打开", Bad)
                Spacer(Modifier.height(14.dp))
            } else {
                StatusTip("NFC 已就绪，将标签贴近手机背面", Accent)
                Spacer(Modifier.height(14.dp))
            }

            // ---- 入口 ----
            EntryCard(
                title = "读取标签",
                desc = "查看标签内容、UID、容量与技术参数",
                enabled = nfcSupported && nfcEnabled,
                onClick = onOpenRead,
            )
            Spacer(Modifier.height(10.dp))

            EntryCard(
                title = "写入标签",
                desc = "文本 / 网址 / WiFi / 名片 / 电话 / 短信",
                enabled = nfcSupported && nfcEnabled,
                onClick = onOpenWrite,
            )
            Spacer(Modifier.height(10.dp))

            EntryCard(
                title = "标签信息",
                desc = "只看硬件信息，不改动标签",
                enabled = nfcSupported && nfcEnabled,
                onClick = onOpenInfo,
            )

            Spacer(Modifier.height(20.dp))

            // ---- 分隔 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(pal.line.copy(alpha = 0.5f)))
                Text(
                    "  其他  ",
                    fontSize = 11.sp,
                    color = pal.inkSoft,
                )
                Box(Modifier.weight(1f).height(1.dp).background(pal.line.copy(alpha = 0.5f)))
            }

            Spacer(Modifier.height(14.dp))

            EntryCard(
                title = "二维码 / 条形码",
                desc = "生成 QR / Code128 / EAN 等 8 种码",
                enabled = true,
                onClick = onOpenCode,
            )

            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun StatusTip(text: String, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, fontSize = 12.5.sp, color = color, lineHeight = 18.sp)
    }
}

@Composable
private fun EntryCard(
    title: String,
    desc: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val pal = LocalPalette.current

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(pal.paperSoft)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) pal.ink else pal.inkSoft.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                desc,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = if (enabled) pal.inkSoft else pal.inkSoft.copy(alpha = 0.5f),
            )
        }

        Text(
            "›",
            fontSize = 20.sp,
            color = if (enabled) pal.inkSoft else pal.inkSoft.copy(alpha = 0.3f),
        )
    }
}
