package com.csdemo.ui.pages

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.ui.theme.Accent
import com.csdemo.ui.theme.Ink
import com.csdemo.ui.theme.InkSoft
import com.csdemo.ui.theme.Line
import com.csdemo.ui.theme.Paper

/**
 * 四个主页面。
 *
 * 设计原则：
 *   · 不破坏原有子页面，点条目仍走原有的 onOpen(route) 路由
 *   · 白底黑字，一个蓝色强调（与项目现有视觉语言一致）
 */

// ---------- 公共布局 ----------

/** 页面标题（顶部大标题） */
@Composable
private fun PageTitle(text: String, subtitle: String = "") {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp)) {
        Text(text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 12.sp, color = InkSoft)
        }
    }
}

/** 分组标题 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = InkSoft,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)
    )
}

/** 一个条目行 */
@Composable
private fun EntryRow(
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = Ink)
            if (desc.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(desc, fontSize = 11.sp, color = InkSoft)
            }
        }
        Text("\u203A", fontSize = 18.sp, color = InkSoft)
    }
}

/** 分隔线 */
@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(start = 20.dp).height(1.dp).background(Line))
}

/** 卡片式分组 */
@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .background(Paper, RoundedCornerShape(16.dp))
    ) {
        Column { content() }
    }
}

// ============================================================
// 页面 1：主页（按要求先做空白页）
// ============================================================

@Composable
fun HomePage() {
    Box(Modifier.fillMaxSize().background(Paper)) {
        // 按要求：此页暂为空白，内容待定
    }
}

// ============================================================
// 页面 2：常用功能（检测 + 网络）
// ============================================================

@Composable
fun FeaturesPage(onOpen: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
    ) {
        PageTitle("常用功能", "检测与网络")

        SectionLabel("检测")
        Card {
            EntryRow("设备信息", "硬件 / 系统 / 电池", onClick = { onOpen("device") })
            Divider()
            EntryRow("Root 检测", "su / Magisk / 提权状态", onClick = { onOpen("root") })
            Divider()
            EntryRow("应用列表", "已安装应用与信息", onClick = { onOpen("apps") })
            Divider()
            EntryRow("编解码", "媒体编解码能力与哈希", onClick = { onOpen("codec") })
        }

        SectionLabel("网络")
        Card {
            EntryRow("Ping", "连通性与延时", onClick = { onOpen("ping") })
            Divider()
            EntryRow("DNS", "域名解析查询", onClick = { onOpen("dns") })
            Divider()
            EntryRow("端口扫描", "常用端口探测", onClick = { onOpen("port") })
            Divider()
            EntryRow("HTTP", "请求与响应查看", onClick = { onOpen("http") })
            Divider()
            EntryRow("局域网", "ARP / 设备发现", onClick = { onOpen("lan") })
        }

        Spacer(Modifier.height(120.dp))
    }
}

// ============================================================
// 页面 3：工具（工具 + root + 逆向）
// ============================================================

@Composable
fun ToolsPage(onOpen: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
    ) {
        PageTitle("工具", "调优 / Root / 逆向")

        SectionLabel("系统调优")
        Card {
            EntryRow("CPU 频率", "各核心频率与调速", onClick = { onOpen("cpu") })
            Divider()
            EntryRow("调度器", "调度参数查看", onClick = { onOpen("sched") })
            Divider()
            EntryRow("温度", "热区与温控", onClick = { onOpen("thermal") })
            Divider()
            EntryRow("线程优化", "线程与优先级", onClick = { onOpen("thread") })
        }

        SectionLabel("Root")
        Card {
            EntryRow("Root 检测", "su / Magisk / 提权", onClick = { onOpen("root") })
            Divider()
            EntryRow("SELinux", "状态与策略", onClick = { onOpen("selinux") })
            Divider()
            EntryRow("设备伪装", "机型 / 芯片伪装", onClick = { onOpen("spoof") })
        }

        SectionLabel("逆向")
        Card {
            EntryRow("ELF 分析", "符号 / 节区 / 控制流 / 伪 C", onClick = { onOpen("elf") })
        }

        Spacer(Modifier.height(120.dp))
    }
}

// ============================================================
// 页面 4：设置（检查更新 / 主题设置 / 关于）
// ============================================================

@Composable
fun SettingsPage(
    onCheckUpdate: () -> Unit,
    onTheme: () -> Unit,
    onAbout: () -> Unit,
    onBottomBarSettings: () -> Unit,
    onScale: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
    ) {
        PageTitle("设置")

        SectionLabel("常规")
        Card {
            EntryRow("检查更新", "查看是否有新版本", onClick = onCheckUpdate)
            Divider()
            EntryRow("主题设置", "深色 / 浅色 / 跟随系统", onClick = onTheme)
            Divider()
            EntryRow("底栏设置", "显示方式 / 缩放", onClick = onBottomBarSettings)
            Divider()
            EntryRow("界面缩放", "调整界面整体大小", onClick = onScale)
        }

        SectionLabel("关于")
        Card {
            EntryRow("关于", "版本 / 开源许可", onClick = onAbout)
        }

        Spacer(Modifier.height(120.dp))
    }
}

/** 设置子项 - 敬请选择态（用于还未实现的具体项） */
@Composable
fun ComingSoonHint(text: String) {
    Box(Modifier.fillMaxSize().background(Paper), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 14.sp, color = InkSoft)
    }
}

/** 设置子项 - 强调色文本（供外部复用，避免未使用报警） */
@Composable
fun AccentText(text: String) {
    Text(text, fontSize = 13.sp, color = Accent)
}

// ============================================================
// 设置子页
// ============================================================

/** 单选行（带勾选标记） */
@Composable
private fun ChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 15.sp, color = Ink, modifier = Modifier.weight(1f))
        if (selected) Text("\u2713", fontSize = 15.sp, color = Accent)
    }
}

/** 提示行（带描述） */
@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 15.sp, color = Ink, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = InkSoft)
    }
}

/** 子页顶部返回栏 */
@Composable
private fun SubPageHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .clickable { onBack() }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text("\u2039 返回", fontSize = 14.sp, color = Accent)
        }
        Spacer(Modifier.weight(1f))
    }
    Text(
        title,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = Ink,
        modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
    )
}

/** 主题模式 */
enum class ThemeMode(val label: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色"),
}

/** 主题设置页 */
@Composable
fun ThemeSettingsPage(
    mode: ThemeMode,
    onModeChange: (ThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())) {
        SubPageHeader("主题设置", onBack)
        Card {
            ThemeMode.entries.forEachIndexed { i, m ->
                if (i > 0) Divider()
                ChoiceRow(m.label, selected = mode == m, onClick = { onModeChange(m) })
            }
        }
        Spacer(Modifier.height(120.dp))
    }
}

/** 检查更新页 */
@Composable
fun CheckUpdatePage(currentVersion: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())) {
        SubPageHeader("检查更新", onBack)
        Card {
            InfoRow("当前版本", currentVersion)
            Divider()
            InfoRow("更新状态", "已是最新")
        }
        Spacer(Modifier.height(120.dp))
    }
}

/** 关于页 */
@Composable
fun AboutPage(version: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())) {
        SubPageHeader("关于", onBack)
        Card {
            InfoRow("版本", version)
            Divider()
            InfoRow("类型", "AArch64 静态分析工具")
        }
        Spacer(Modifier.height(120.dp))
    }
}

/** 底栏设置页 */
@Composable
fun BottomBarSettingsPage(
    floating: Boolean,
    blurEnabled: Boolean,
    onFloatingChange: (Boolean) -> Unit,
    onBlurChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())) {
        SubPageHeader("底栏设置", onBack)
        SectionLabel("外观")
        Card {
            ChoiceRow("浮动底栏", selected = floating, onClick = { onFloatingChange(true) })
            Divider()
            ChoiceRow("固定底栏", selected = !floating, onClick = { onFloatingChange(false) })
        }
        SectionLabel("效果")
        Card {
            ChoiceRow("开启毛玻璃", selected = blurEnabled, onClick = { onBlurChange(true) })
            Divider()
            ChoiceRow("关闭毛玻璃", selected = !blurEnabled, onClick = { onBlurChange(false) })
        }
        Spacer(Modifier.height(120.dp))
    }
}

/** 界面缩放页 */
@Composable
fun ScalePage(
    scale: Float,
    onScaleChange: (Float) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())) {
        SubPageHeader("界面缩放", onBack)
        SectionLabel("缩放比例")
        Card {
            listOf(0.85f, 1.0f, 1.15f, 1.3f).forEachIndexed { i, s ->
                if (i > 0) Divider()
                ChoiceRow(
                    title = (s * 100).toInt().toString() + "%",
                    selected = kotlin.math.abs(scale - s) < 0.01f,
                    onClick = { onScaleChange(s) },
                )
            }
        }
        Spacer(Modifier.height(120.dp))
    }
}
