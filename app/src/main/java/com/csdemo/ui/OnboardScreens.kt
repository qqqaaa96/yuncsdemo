package com.csdemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.tools.Plan
import com.csdemo.ui.theme.Accent
import com.csdemo.ui.theme.Bad
import com.csdemo.ui.theme.Good
import com.csdemo.ui.theme.Ink
import com.csdemo.ui.theme.InkSoft
import com.csdemo.ui.theme.Line
import com.csdemo.ui.theme.Paper
import com.csdemo.ui.theme.PaperSoft
import com.csdemo.ui.theme.Warn

/**
 * 隐私政策页。首屏。
 */
@Composable
fun PrivacyScreen(onAgree: () -> Unit, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Paper)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            Text("使用声明与隐私政策", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text("版本 1.0　生效日期 2026-09-29", fontSize = 11.sp, color = InkSoft)
            Spacer(Modifier.height(16.dp))

            // ---------- 首屏醒目警告 ----------
            DangerBlock()

            // ---------- 一、安全须知 ----------
            BigSection("一、安全须知（优先阅读）")
            PolicySection(
                "不具备救砖能力，请勿使用 Root 功能",
                "Root 功能会以最高权限修改系统底层参数。一旦参数不当，设备可能无法开机。\n\n" +
                        "如果你不具备独立救砖能力，包括但不限于：\n" +
                        "· 高通 9008 / EDL 刷机\n" +
                        "· MTK SP Flash Tool 线刷\n" +
                        "· Fastboot 刷写与回滚\n" +
                        "· 已具备可用的线刷包与驱动\n" +
                        "· 了解如何进入 recovery / bootloader\n\n" +
                        "请立即终止使用 Root 相关功能。设备变砖可能造成不可逆转的后果。"
            )

            // ---------- 二、功能说明 ----------
            BigSection("二、功能说明")

            FeatureBlock(
                "设备信息", "低风险", Dangerous.NO,
                "读取本机型号、Soc、内存、存储、屏幕、电池、传感器、网络状态。" +
                        "全部为只读操作，不修改任何参数。"
            )
            FeatureBlock(
                "Root 检测", "低风险", Dangerous.NO,
                "检测设备是否已 root，包括 su 存在性、Root 管理器痕迹，并实际尝试执行 su 命令验证授权状态。" +
                        "本页只读，不会提权成功后就自动做任何事。"
            )
            FeatureBlock(
                "应用列表", "低风险", Dangerous.NO,
                "列出已安装应用的包名、版本、签名指纹、权限与组件数量。" +
                        "仅本机展示，不具备安装、卸载、冻结能力。"
            )
            FeatureBlock(
                "Ping / DNS / 端口扫描 / HTTP / 局域网", "中风险", Dangerous.MAYBE,
                "向目标发网络请求。\n\n" +
                        "→ 请仅对你拥有或已获授权的目标使用。未经授权扫描他人主机、网络，可能违反法律法规。"
            )
            FeatureBlock(
                "编码 / 哈希", "无风险", Dangerous.NO,
                "Base64 / URL / Hex / HTML 编码与 MD5、SHA 系列哈希，纯本地计算。"
            )
            FeatureBlock(
                "CPU 频率 / 调度 / 温控墙 / 线程优化", "高风险", Dangerous.YES,
                "直接读写内核参数（/sys 与 /proc）。\n\n" +
                        "→ 不当的频率、调度或温控设置可能引起过热、耗电異常、系统不稳定；" +
                        "极端情况可能导致设备无法正常启动。\n" +
                        "→ 所有写入仅在你手动点击时执行，不会自动应用。\n" +
                        "→ 重启后大部分设置会自动恢复。"
            )
            FeatureBlock(
                "设备伪装", "中风险", Dangerous.MAYBE,
                "修改系统属性（ro.product.* / ro.soc.* 等）。\n\n" +
                        "→ 使用 resetprop 时为内存级修改，不写入分区，重启自动恢复。\n" +
                        "→ 修改机型/芯片可能使部分应用（银行、游戏）行为异常，重启即可恢复。\n" +
                        "→ 本功能不会修改 boot、system、vendor 等分区，不会导致变砖。"
            )
            FeatureBlock(
                "SELinux 管理", "高风险", Dangerous.YES,
                "查看当前模式并在强制 / 宽容之间切换。\n\n" +
                        "→ 宽容模式会使系统安全策略失效，应用隔离形同虚设。\n" +
                        "→ 仅建议在调试自己的设备时临时使用，用完立即切回强制模式。\n" +
                        "→ 重启后通常恢复为系统默认。"
            )

            // ELF 逆向单独说明
            FeatureBlock(
                "ELF 逆向", "含内容风险", Dangerous.MAYBE,
                "上传 .so / ELF 文件，进行解析、反汇编、控制流图与字符串查看。\n\n" +
                        "→ 本功能为纯离线分析，不上传任何文件。\n" +
                        "→ “字符串修改”为原地覆写：仅允许新内容不超原长度的修改，不改指针、" +
                        "不改长度字段，因此不会破坏 ELF 结构；超长修改会被拒绝。\n" +
                        "→ 产出文件仅保存在本应用私有目录，不覆盖你的原文件。\n" +
                        "→ 请确保你拥有对所分析文件的合法权利，不得用于破解他人软件或绕过授权验证。"
            )

            BigSection("三、使用声明")
            PolicySection(
                "自行承担风险",
                "本应用按“现状”提供，不对适用性、稳定性、安全性作任何明示或默示担保。" +
                        "你使用本应用（尤其是 Root 相关功能）所产生的任何直接或间接后果，由你自行承担。"
            )
            PolicySection(
                "合法使用",
                "你承诺仅将本应用用于合法用途，并仅对自己拥有或已获得明确授权的设备、" +
                        "网络、软件使用相关功能。禁止用于未经授权的入侵、破解、监听等行为。"
            )
            PolicySection(
                "数据备份",
                "在修改系统参数或挂载分区前，请自行备份重要数据。本应用不提供备份与恢复功能，" +
                        "也不对数据丢失负责。"
            )
            PolicySection(
                "保修影响",
                "获取 Root 权限、解锁 Bootloader、修改系统分区等行为可能使设备失去官方保修，" +
                        "也可能影响系统更新。请自行评估。"
            )
            PolicySection(
                "无网络服务",
                "本应用不提供任何云服务、账号体系或在线功能。所有计算均在本机完成，" +
                        "不存在服务中断、数据外泄风险。"
            )

            BigSection("四、法律声明")
            PolicySection(
                "责任限制",
                "在法律允许的最大范围内，开发者不对因使用或无法使用本应用而产生的任何损失负责，" +
                        "包括但不限于设备损坏、数据丢失、业务中断、利润损失。"
            )
            PolicySection(
                "合规使用",
                "你应遵守所在国家与地区的法律法规。对网络进行扫描、探测前，请确认你已获得授权。" +
                        "因违规使用产生的法律责任由你自行承担。"
            )
            PolicySection(
                "知识产权",
                "本应用的名称、界面与代码归开发者所有。你可在授权范围内使用，不得反向工程、" +
                        "二次分发或以商业目的使用。"
            )
            PolicySection(
                "免责范围",
                "本应用不包含任何破解工具、病毒、恶意代码，也不提供绕过他人安全机制的能力。" +
                        "若你以非预期方式使用，后果自负。"
            )

            BigSection("五、注意事项")
            NoteList(
                listOf(
                    "· 修改 CPU 频率前，先确认设备散热正常，避免边充电边高强度使用。",
                    "· 修改温控墙可能绕过过热保护，风险较高，请谨慎。",
                    "· 不要在未备份的情况下挂载系统分区为可写。",
                    "· 端口扫描 / 局域网扫描仅限自己的网络，公共网络下可能引起报警。",
                    "· 设备伪装可能影响部分应用的完整性校验，遇到异常先一键恢复。",
                    "· 修改任何参数后若出现异常，首先尝试重启恢复。",
                    "· 若重启后仍异常，可进入 recovery 清除数据；仍不行则需线刷救砖。",
                    "· 本应用不提供任何自动修改能力，每一步都需要你主动触发。"
                )
            )

            BigSection("六、隐私政策")
            PolicySection(
                "不收集任何信息",
                "本应用完全本地运行：不设服务器、不回传数据、不埋点、不接入统计与广告 SDK。"
            )
            PolicySection(
                "数据存储",
                "仅在本机保存你的方案选择与少量偏好设置，卸载即删除。"
            )
            PolicySection(
                "权限说明",
                "INTERNET / ACCESS_NETWORK_STATE：网络工具。\n" +
                        "QUERY_ALL_PACKAGES：应用列表。\n" +
                        "ACCESS_FINE / COARSE_LOCATION：部分设备读取 Wi-Fi 信息所需。\n" +
                        "以上权限涉及的数据均仅在本机使用。"
            )
            PolicySection(
                "你的权利",
                "你可以选择不同意并退出，或随时卸载本应用。"
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "继续即表示你已阅读、理解并同意以上全部声明。",
                fontSize = 12.sp, color = Ink, fontWeight = FontWeight.Medium, lineHeight = 18.sp
            )
            Spacer(Modifier.height(30.dp))
        }

        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Button(
                onClick = onAgree,
                modifier = Modifier.fillMaxWidth()
            ) { Text("同意并继续") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth()
            ) { Text("不同意", color = InkSoft) }
        }
    }
}

@Composable
private fun PolicySection(title: String, body: String) {
    Spacer(Modifier.height(14.dp))
    Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    Spacer(Modifier.height(4.dp))
    Text(body, fontSize = 13.sp, color = InkSoft, lineHeight = 20.sp)
}

/** 大标题分隔 */
@Composable
private fun BigSection(title: String) {
    Spacer(Modifier.height(22.dp))
    Box(
        Modifier.fillMaxWidth().height(1.dp).background(Line)
    )
    Spacer(Modifier.height(12.dp))
    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
}

/** 风险等级 */
private enum class Dangerous { NO, MAYBE, YES }

/** 功能说明条目：标题 + 风险标签 + 正文 */
@Composable
private fun FeatureBlock(title: String, risk: String, level: Dangerous, body: String) {
    val color = when (level) {
        Dangerous.NO -> Good
        Dangerous.MAYBE -> Warn
        Dangerous.YES -> Bad
    }
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .background(color.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(5.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Text(risk, fontSize = 10.sp, color = color, fontWeight = FontWeight.Medium)
        }
    }
    Spacer(Modifier.height(5.dp))
    Text(body, fontSize = 12.5.sp, color = InkSoft, lineHeight = 19.sp)
}

/** 顶部醒目警告块 */
@Composable
private fun DangerBlock() {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.5.dp, Bad, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(Bad.copy(alpha = 0.06f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚠", fontSize = 18.sp, color = Bad)
            Spacer(Modifier.width(8.dp))
            Text("重要警告", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Bad)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "本应用包含 Root 相关功能，会以最高权限修改系统底层参数。",
            fontSize = 12.5.sp, color = Ink, lineHeight = 19.sp, fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "如果你不具备独立救砖能力（如高通 9008、MTK 线刷、Fastboot 刷写等），" +
                    "请务必终止使用 Root 功能。不当修改可能造成设备无法开机，且后果不可逆转。",
            fontSize = 12.5.sp, color = Bad, lineHeight = 19.sp
        )
    }
}

/** 注意事项列表 */
@Composable
private fun NoteList(items: List<String>) {
    Spacer(Modifier.height(6.dp))
    items.forEach {
        Text(it, fontSize = 12.5.sp, color = InkSoft,
            lineHeight = 19.sp, modifier = Modifier.padding(vertical = 3.dp))
    }
}

/**
 * 方案选择页。
 */
@Composable
fun PlanScreen(onPicked: () -> Unit) {
    val ctx = LocalContext.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
    ) {
        Text("选择方案", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text("两种方案使用同一套工具箱，区别是 Root 功能是否可用。可随时在主页切换。",
            fontSize = 12.sp, color = InkSoft, lineHeight = 18.sp)
        Spacer(Modifier.height(20.dp))

        PlanCard(
            index = 0,
            title = "基础方案",
            tag = "免 Root",
            tagColor = InkSoft,
            lines = listOf(
                "设备信息 / 应用列表",
                "Ping / DNS / 端口扫描",
                "HTTP 检测 / 局域网扫描",
                "编码 / 哈希"
            ),
            disabled = listOf("CPU 频率调节", "调度设置", "温控墙", "线程优化")
        ) {
            Plan.setPlan(ctx, Plan.BASIC)
            onPicked()
        }

        Spacer(Modifier.height(14.dp))

        PlanCard(
            index = 1,
            title = "Root 方案",
            tag = "需 Root",
            tagColor = Good,
            lines = listOf(
                "包含基础方案全部功能",
                "CPU 频率调节",
                "调度（governor / IO）",
                "温控墙设置",
                "线程优化"
            ),
            disabled = emptyList()
        ) {
            Plan.setPlan(ctx, Plan.ROOT)
            onPicked()
        }

        Spacer(Modifier.height(20.dp))
        Text("注：选择 Root 方案后，若设备未获取 Root，相关功能会提示授权，不会伪装可用。",
            fontSize = 11.sp, color = InkSoft, lineHeight = 16.sp)
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun PlanCard(
    index: Int,
    title: String,
    tag: String,
    tagColor: androidx.compose.ui.graphics.Color,
    lines: List<String>,
    disabled: List<String>,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .staggerIn(index)
            .pressable(pressedScale = 0.975f, onClick = onClick)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .background(Paper, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.width(10.dp))
            Tag(tag, tagColor)
        }
        Spacer(Modifier.height(12.dp))
        lines.forEach { l ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text("✓", fontSize = 12.sp, color = Good)
                Spacer(Modifier.width(8.dp))
                Text(l, fontSize = 13.sp, color = Ink)
            }
        }
        if (disabled.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("不含以下功能", fontSize = 11.sp, color = InkSoft)
            Spacer(Modifier.height(4.dp))
            disabled.forEach { l ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text("—", fontSize = 12.sp, color = InkSoft)
                    Spacer(Modifier.width(8.dp))
                    Text(l, fontSize = 13.sp, color = InkSoft)
                }
            }
        }
    }
}
