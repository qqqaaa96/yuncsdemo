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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.tools.Plan
import com.csdemo.ui.FEATURES
import com.csdemo.ui.Feature
import com.csdemo.ui.HLine
import com.csdemo.ui.RowItem
import com.csdemo.ui.pressable
import com.csdemo.ui.staggerIn
import com.csdemo.ui.theme.Accent
import com.csdemo.ui.theme.Ink
import com.csdemo.ui.theme.InkFaint
import com.csdemo.ui.theme.InkSoft
import com.csdemo.ui.theme.Line
import com.csdemo.ui.theme.Paper
import com.csdemo.ui.theme.PaperSoft

/**
 * 四个主页面。
 *
 * 严格沿用项目原有 UI 风格（与 Home.kt 一致的卡片网格 / 标题 / 动效），
 * 只把原有功能按 4 页分开，不自造新样式。
 */

// ---------- 与 Home.kt 一致的卡片（原样复刻） ----------

@Composable
private fun FeatureCard(f: Feature, locked: Boolean, index: Int, onClick: () -> Unit) {
    val nameColor = if (locked) InkFaint else Ink
    val descColor = if (locked) InkFaint else InkSoft
    val iconColor = if (locked) InkFaint else Accent
    Column(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .staggerIn(index)
            .pressable(pressedScale = 0.955f, onClick = onClick)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .background(if (locked) PaperSoft else Paper, RoundedCornerShape(12.dp))
            .padding(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.icon, fontSize = 17.sp, color = iconColor)
            Spacer(Modifier.weight(1f))
            if (locked) {
                Text("锁定", fontSize = 10.sp, color = InkFaint)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = nameColor)
        Text(f.desc, fontSize = 11.sp, color = descColor)
    }
}

/** 筛选指定分组的特性，并按需剔除 id */
private fun featuresOf(groups: Set<String>, exclude: Set<String> = emptySet()): List<Feature> =
    FEATURES.filter { it.group in groups && it.id !in exclude }

/** 卡片网格（与 Home.kt 一致的分组 + 2 列布局） */
@Composable
private fun FeatureGrid(
    items: List<Feature>,
    onOpen: (String) -> Unit,
    onLocked: (String) -> Unit,
) {
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
                    group, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = InkSoft,
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

/** 顶部大标题（与 Home.kt 一致） */
@Composable
private fun BigTitle(title: String, subtitle: String = "") {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 12.sp, color = InkSoft)
        }
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
fun FeaturesPage(onOpen: (String) -> Unit, onLocked: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(Paper)) {
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
    Column(Modifier.fillMaxSize().background(Paper)) {
        BigTitle("工具", "调优 / Root / 逆向")
        FeatureGrid(
            items = featuresOf(setOf("工具", "Root", "逆向")),
            onOpen = onOpen,
            onLocked = onLocked,
        )
    }
}

// ============================================================
// 页面 4：设置（检查更新 / 主题设置 / 底栏设置 / 界面缩放 / 关于）
// ============================================================

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = InkSoft,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Line, RoundedCornerShape(12.dp))
                .background(Paper, RoundedCornerShape(12.dp))
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
    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
    ) {
        BigTitle("设置")

        SettingsGroup("常规") {
            RowItem("检查更新", "查看是否有新版本", "\u203A", onClick = onCheckUpdate)
            HLine()
            RowItem("主题设置", "深色 / 浅色 / 跟随系统", "\u203A", onClick = onTheme)
            HLine()
            RowItem("底栏设置", "显示方式 / 回归模板长度", "\u203A", onClick = onBottomBarSettings)
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

/** 子页顶部返回栏（与项目 Accent 风格一致） */
@Composable
private fun SubHeader(title: String, onBack: () -> Unit) {
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
            Text("\u2039 返回", fontSize = 14.sp, color = Accent)
        }
        Spacer(Modifier.weight(1f))
    }
    Text(
        title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink,
        modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 8.dp)
    )
}

/** 选项行（带勾选） */
@Composable
private fun ChoiceItem(title: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.99f, onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = Ink)
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(sub, fontSize = 11.sp, color = InkSoft)
            }
        }
        if (selected) Text("\u2713", fontSize = 15.sp, color = Accent)
    }
}

/** 主题模式 */
enum class ThemeMode(val label: String, val sub: String) {
    System("跟随系统", "随系统深色开关自动切换"),
    Light("浅色", "始终使用浅色界面"),
    Dark("深色", "始终使用深色界面"),
}

@Composable
fun ThemeSettingsPage(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())) {
        SubHeader("主题设置", onBack)
        SettingsGroup("外观模式") {
            ThemeMode.entries.forEachIndexed { i, m ->
                if (i > 0) HLine()
                ChoiceItem(m.label, m.sub, mode == m) { onModeChange(m) }
            }
        }
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
fun CheckUpdatePage(currentVersion: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())) {
        SubHeader("检查更新", onBack)
        SettingsGroup("版本信息") {
            RowItem("当前版本", null, currentVersion, onClick = {})
            HLine()
            RowItem("更新状态", null, "已是最新", onClick = {})
        }
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
fun AboutPage(version: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())) {
        SubHeader("关于", onBack)
        SettingsGroup("信息") {
            RowItem("版本", null, version, onClick = {})
            HLine()
            RowItem("类型", "AArch64 静态分析工具", "", onClick = {})
        }
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
fun BottomBarSettingsPage(
    floating: Boolean,
    blurEnabled: Boolean,
    onFloatingChange: (Boolean) -> Unit,
    onBlurChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())) {
        SubHeader("底栏设置", onBack)
        SettingsGroup("显示方式") {
            ChoiceItem("浮动底栏", "悬浮于内容之上的液态玻璃底栏", floating) { onFloatingChange(true) }
            HLine()
            ChoiceItem("固定底栏", "紧贴屏幕底部的常规底栏", !floating) { onFloatingChange(false) }
        }
        SettingsGroup("效果") {
            ChoiceItem("开启毛玻璃", "液态折射 + 背景模糊", blurEnabled) { onBlurChange(true) }
            HLine()
            ChoiceItem("关闭毛玻璃", "纯色底栏，性能更好", !blurEnabled) { onBlurChange(false) }
        }
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
fun ScalePage(scale: Float, onScaleChange: (Float) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())) {
        SubHeader("界面缩放", onBack)
        SettingsGroup("缩放比例") {
            val options = listOf(0.85f, 1.0f, 1.15f, 1.3f)
            options.forEachIndexed { i, s ->
                if (i > 0) HLine()
                ChoiceItem(
                    title = (s * 100).toInt().toString() + "%",
                    sub = null,
                    selected = kotlin.math.abs(scale - s) < 0.01f,
                ) { onScaleChange(s) }
            }
        }
        Spacer(Modifier.height(120.dp))
    }
}
