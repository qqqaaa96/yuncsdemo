package com.csdemo.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.tools.AppSettings
import com.csdemo.tools.Plan
import com.csdemo.tools.RootCheck
import com.csdemo.ui.FEATURES
import com.csdemo.ui.Feature
import com.csdemo.ui.HLine
import com.csdemo.ui.RowItem
import com.csdemo.ui.pressable
import com.csdemo.ui.staggerIn
import com.csdemo.ui.theme.LocalPalette

/**
 * 四个主页面。
 *
 * 主页（页面1）调用 com.csdemo.ksu.HomePagerMiuix，即 KernelSU 主页 UI 的
 * 完整复制；其余页面沿用项目原有 UI 风格。
 */

// ============================================================
// 页面 1：主页 —— KernelSU 主页 UI（完整复制）
//
// 数据来源换成本项目的 RootCheck / Selinux：
//   · root 已授权 → ksuVersion 非空 → 绿色卡片（文字：“授权 su 成功”）
//   · root 未授权 → ksuVersion 为 null → “未安装”卡片
// ============================================================

@Composable
fun HomePage() {
    var report by remember { mutableStateOf<RootCheck.Report?>(null) }
    var selinux by remember { mutableStateOf("Enforcing") }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val pair = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val r = RootCheck.scan()
            val s = com.csdemo.tools.Selinux.read().mode
            r to when (s) {
                com.csdemo.tools.Selinux.Mode.ENFORCING -> "Enforcing"
                com.csdemo.tools.Selinux.Mode.PERMISSIVE -> "Permissive"
                com.csdemo.tools.Selinux.Mode.DISABLED -> "Disabled"
                else -> "Unknown"
            }
        }
        report = pair.first
        selinux = pair.second
    }

    val granted = report?.granted == true
    val state = com.csdemo.ksu.HomeUiState(
        kernelVersion = com.csdemo.ksu.parseKernelVersion(System.getProperty("os.version") ?: ""),
        ksuVersion = if (granted) 1 else null,
        managerUAPIVersion = 1,
        kernelUAPIVersion = 1,
        lkmMode = null,
        isLkmBundled = false,
        isManager = true,
        isManagerPrBuild = false,
        isKernelPrBuild = false,
        requiresNewKernel = false,
        requiresNewManager = false,
        isRootAvailable = granted,
        isSafeMode = false,
        isLateLoadMode = false,
        checkUpdateEnabled = false,
        latestVersionInfo = com.csdemo.ksu.LatestVersionInfo(),
        currentManagerVersionCode = 1L,
        systemInfo = com.csdemo.ksu.SystemInfo(
            kernelVersion = System.getProperty("os.version") ?: "未知",
            managerVersion = "1.0",
            deviceModel = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
            fingerprint = android.os.Build.FINGERPRINT,
            selinuxStatus = selinux,
            seccompStatus = 2,
        ),
    )
    val actions = com.csdemo.ksu.HomeActions(
        onInstallClick = { },
        onOpenUrl = { },
    )
    com.csdemo.ksu.HomePagerMiuix(
        state = state,
        actions = actions,
        bottomInnerPadding = 140.dp,
    )
}

// ============================================================
// 通用：卡片网格（沿用项目原有 UI 风格）
// ============================================================

@Composable
private fun FeatureCard(f: Feature, locked: Boolean, index: Int, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val nameColor = if (locked) pal.inkFaint else pal.ink
    val descColor = if (locked) pal.inkFaint else pal.inkSoft
    val iconColor = if (locked) pal.inkFaint else pal.accent
    Column(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .staggerIn(index)
            .pressable(pressedScale = 0.955f, onClick = onClick)
            .border(1.dp, pal.line, RoundedCornerShape(12.dp))
            .background(if (locked) pal.paperSoft else pal.paper, RoundedCornerShape(12.dp))
            .padding(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.icon, fontSize = 17.sp, color = iconColor)
            Spacer(Modifier.weight(1f))
            if (locked) {
                Text("锁定", fontSize = 10.sp, color = pal.inkFaint)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = nameColor)
        Text(f.desc, fontSize = 11.sp, color = descColor)
    }
}

private fun featuresOf(groups: Set<String>): List<Feature> =
    FEATURES.filter { it.group in groups }

@Composable
private fun FeatureGrid(
    items: List<Feature>,
    onOpen: (String) -> Unit,
    onLocked: (String) -> Unit,
) {
    val pal = LocalPalette.current
    val groups = items.groupBy { it.group }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        groups.forEach { (group, groupItems) ->
            item(span = { GridItemSpan(2) }) {
                Text(
                    group, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.inkSoft,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            itemsIndexed(groupItems, key = { _, f -> f.id }) { idx, f ->
                val locked = f.needRoot && !Plan.canUseRoot()
                FeatureCard(f, locked, idx) {
                    if (locked) onLocked(f.name) else onOpen(f.id)
                }
            }
        }
        item(span = { GridItemSpan(2) }) {
            Spacer(Modifier.height(120.dp))
        }
    }
}

@Composable
private fun BigTitle(title: String, subtitle: String = "") {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = pal.ink)
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 12.sp, color = pal.inkSoft)
        }
    }
}

// ============================================================
// 页面 2：常用功能（检测 + 网络）
// ============================================================

@Composable
fun FeaturesPage(onOpen: (String) -> Unit, onLocked: (String) -> Unit) {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxSize().background(pal.paper)) {
        BigTitle("常用功能", "检测与网络")
        FeatureGrid(
            items = featuresOf(setOf("检测", "网络")),
            onOpen = onOpen,
            onLocked = onLocked,
        )
    }
}

// ============================================================
// 页面 3：工具（工具 + Root + 逆向）
// ============================================================

@Composable
fun ToolsPage(onOpen: (String) -> Unit, onLocked: (String) -> Unit) {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxSize().background(pal.paper)) {
        BigTitle("工具", "调优 / Root / 逆向")
        FeatureGrid(
            items = featuresOf(setOf("工具", "Root", "逆向")),
            onOpen = onOpen,
            onLocked = onLocked,
        )
    }
}

// ============================================================
// 页面 4：设置
// ============================================================

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.inkSoft,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, pal.line, RoundedCornerShape(12.dp))
                .background(pal.paper, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp)
        ) {
            content()
        }
    }
}

@Composable
fun SettingsPage(
    onCheckUpdate: () -> Unit,
    onTheme: () -> Unit,
    onAbout: () -> Unit,
    onBottomBarSettings: () -> Unit,
    onScale: () -> Unit,
) {
    val pal = LocalPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .background(pal.paper)
            .verticalScroll(rememberScrollState())
    ) {
        BigTitle("设置")

        SettingsGroup("常规") {
            RowItem("检查更新", "查看是否有新版本", "\u203A", onClick = onCheckUpdate)
            HLine()
            RowItem("主题设置", "深色 / 浅色 / 跟随系统", "\u203A", onClick = onTheme)
            HLine()
            RowItem("底栏设置", "浮动 / 玻璃 / 界面缩放", "\u203A", onClick = onBottomBarSettings)
            HLine()
            RowItem("界面缩放", "调整界面整体大小", "\u203A", onClick = onScale)
        }

        SettingsGroup("关于") {
            RowItem("关于", "版本 / 类型", "\u203A", onClick = onAbout)
        }

        Spacer(Modifier.height(120.dp))
    }
}

// ============================================================
// 设置子页
// ============================================================

@Composable
private fun SubHeader(title: String, onBack: () -> Unit) {
    val pal = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 16.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .pressable(pressedScale = 0.95f, onClick = onBack)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("\u2039 返回", fontSize = 14.sp, color = pal.accent)
        }
        Spacer(Modifier.weight(1f))
    }
    Text(
        title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = pal.ink,
        modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 8.dp)
    )
}

@Composable
private fun ChoiceItem(title: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.99f, onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = pal.ink)
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(sub, fontSize = 11.sp, color = pal.inkSoft)
            }
        }
        if (selected) Text("\u2713", fontSize = 15.sp, color = pal.accent)
    }
}

@Composable
private fun SwitchItem(
    title: String,
    sub: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val pal = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.99f) { onCheckedChange(!checked) }
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = pal.ink)
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(sub, fontSize = 11.sp, color = pal.inkSoft)
            }
        }
        Box(
            Modifier
                .width(38.dp)
                .height(22.dp)
                .background(if (checked) pal.accent else pal.line, RoundedCornerShape(50)),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(16.dp)
                    .background(pal.paper, RoundedCornerShape(50))
            )
        }
    }
}

@Composable
fun ThemeSettingsPage(onBack: () -> Unit) {
    val pal = LocalPalette.current
    val s = AppSettings
    Column(Modifier.fillMaxSize().background(pal.paper).verticalScroll(rememberScrollState())) {
        SubHeader("主题设置", onBack)

        SettingsGroup("外观模式") {
            AppSettings.ThemeMode.entries.forEachIndexed { i, m ->
                if (i > 0) HLine()
                ChoiceItem(m.label, m.sub, s.themeMode.value == m) { s.setThemeMode(m) }
            }
        }

        SettingsGroup("显示") {
            SwitchItem(
                title = "背景模糊",
                sub = "液态玻璃折射效果（Android 13+）",
                checked = s.enableBlur.value,
                onCheckedChange = { s.setEnableBlur(it) },
            )
            HLine()
            SwitchItem(
                title = "浮动底栏",
                sub = "悬浮于内容之上",
                checked = s.floatingBottomBar.value,
                onCheckedChange = { s.setFloatingBottomBar(it) },
            )
            HLine()
            SwitchItem(
                title = "玻璃效果",
                sub = "底栏液态折射与高光",
                checked = s.glassBottomBar.value,
                onCheckedChange = { s.setGlassBottomBar(it) },
            )
            HLine()
            SwitchItem(
                title = "导航徽标",
                sub = "底栏显示提示小圆点",
                checked = s.navigationBadge.value,
                onCheckedChange = { s.setNavigationBadge(it) },
            )
        }

        SettingsGroup("界面缩放") {
            val options = listOf(0.8f, 0.9f, 1.0f, 1.1f)
            options.forEachIndexed { i, sc ->
                if (i > 0) HLine()
                ChoiceItem(
                    title = (sc * 100).toInt().toString() + "%",
                    sub = null,
                    selected = kotlin.math.abs(s.pageScale.value - sc) < 0.01f,
                ) { s.setPageScale(sc) }
            }
        }

        Spacer(Modifier.height(120.dp))
    }
}

@Composable
fun CheckUpdatePage(currentVersion: String, onBack: () -> Unit) {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxSize().background(pal.paper).verticalScroll(rememberScrollState())) {
        SubHeader("检查更新", onBack)
        SettingsGroup("版本信息") {
            RowItem("当前版本", null, currentVersion, onClick = {})
            HLine()
            RowItem("更新渠道", null, "正式版", onClick = {})
            HLine()
            RowItem("更新状态", null, "已是最新", onClick = {})
        }
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
fun AboutPage(version: String, onBack: () -> Unit) {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxSize().background(pal.paper).verticalScroll(rememberScrollState())) {
        SubHeader("关于", onBack)
        SettingsGroup("信息") {
            RowItem("版本", null, version, onClick = {})
            HLine()
            RowItem("类型", "AArch64 静态分析工具", "", onClick = {})
            HLine()
            RowItem("底栏", "液态玻璃 · miuix-kmp", "", onClick = {})
        }
        Spacer(Modifier.height(120.dp))
    }
}
