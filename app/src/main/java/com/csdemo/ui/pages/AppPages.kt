package com.csdemo.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.Color
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
 * 严格沿用项目原有 UI 风格（与 Home.kt 一致的卡片网格 / 标题 / 动效），
 * 只把原有功能按 4 页分开，不自造新样式。
 *
 * 颜色全部从 LocalPalette 取，因此会跟随主题设置的深/浅色切换。
 */

// ---------- 与 Home.kt 一致的卡片（原样复刻，颜色改为跟随主题） ----------

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

/** 筛选指定分组的特性 */
private fun featuresOf(groups: Set<String>): List<Feature> =
    FEATURES.filter { it.group in groups }

/** 卡片网格（与 Home.kt 一致的分组 + 2 列布局） */
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

/** 顶部大标题（与 Home.kt 一致，颜色跟随主题） */
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
// 页面 1：主页（按要求先做空白页）
// ============================================================
// 页面 1：主页 = KernelSU 主页 UI
//
// 结构与 KernelSU 的 HomeMiuix.kt 完全一致（StatusCard / InfoCard /
// SupportLinks 逐行搬过来），只改两处逻辑：
//   · 判断条件：从“ksuVersion != null” 改为“root 是否已授权”
//   · 文案： “工作中” 改为 “授权 su 成功”
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
    HomePagerMiuix(report, selinux)
}

/**
 * 对应 KernelSU 的 HomePagerMiuix：Scaffold + TopAppBar + LazyColumn。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun HomePagerMiuix(report: RootCheck.Report?, selinux: String) {
    top.yukonga.miuix.kmp.basic.Scaffold(
        topBar = {
            top.yukonga.miuix.kmp.basic.TopAppBar(
                title = "工具箱",
            )
        },
    ) { innerPadding ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
            overscrollEffect = null,
        ) {
            item {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusCard(report = report, selinux = selinux)
                    InfoCard(report = report, selinux = selinux, modifier = Modifier.fillMaxWidth())
                    SupportLinks(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

// ---- KernelSU 原配色 ----
private val GreenCardLight = Color(0xFFDFFAE4)
private val GreenCardDark = Color(0xFF1A3825)
private val GreenAccent = Color(0xFF36D167)

/**
 * 对应 KernelSU 的 StatusCard。
 *
 * 授权成功 → 绿色卡片（与 KernelSU “工作中”完全一致的布局与尺寸）。
 * 未授权   → BasicComponent 卡片（与 KernelSU “未安装”一致）。
 */
@Composable
private fun StatusCard(report: RootCheck.Report?, selinux: String) {
    val loading = report == null
    if (loading) {
        top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth()) {
            top.yukonga.miuix.kmp.basic.BasicComponent(
                title = "正在检测 Root",
                summary = "请稍候...",
            )
        }
        return
    }

    val granted = report.granted
    val pal = LocalPalette.current
    val dynamic = top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor

    if (granted) {
        // === 授权成功：对应 KernelSU “工作中” ===
        top.yukonga.miuix.kmp.basic.Card(
            modifier = Modifier.fillMaxWidth(),
            colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
                color = when {
                    dynamic -> top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.secondaryContainer
                    isSystemInDarkTheme() -> GreenCardDark
                    else -> GreenCardLight
                }
            ),
        ) {
            Box {
                // 右下角大对勾（自绘，不依赖图标库）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp, 26.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    CheckGlyph(
                        size = 96.dp,
                        color = if (dynamic) {
                            top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
                        } else GreenAccent,
                    )
                }
                // 左上角文字
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp, 14.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Column {
                        top.yukonga.miuix.kmp.basic.Text(
                            text = "授权 su 成功",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(1.dp))
                        top.yukonga.miuix.kmp.basic.Text(
                            text = report.manager.ifBlank {
                                report.suVersion.ifBlank { "su 已获得 root 权限" }
                            },
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }
    } else {
        // === 未授权：对应 KernelSU “未安装” ===
        top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth()) {
            top.yukonga.miuix.kmp.basic.BasicComponent(
                title = "未安装",
                summary = if (report.hasSu) "su 存在，但未授予权限" else "未检测到可用的 su",
                startAction = {
                    WarnGlyph(
                        size = 24.dp,
                        color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        }
    }
}

/** 自绘对勾（圆环 + 勾），对应 KernelSU 的 CheckCircleOutline */
@Composable
private fun CheckGlyph(size: androidx.compose.ui.unit.Dp, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = s * 0.07f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
        // 圆环
        drawCircle(
            color = color,
            radius = s * 0.42f,
            style = stroke,
        )
        // 勾
        val p1 = androidx.compose.ui.geometry.Offset(s * 0.30f, s * 0.52f)
        val p2 = androidx.compose.ui.geometry.Offset(s * 0.44f, s * 0.66f)
        val p3 = androidx.compose.ui.geometry.Offset(s * 0.72f, s * 0.36f)
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
            lineTo(p3.x, p3.y)
        }
        drawPath(path, color = color, style = stroke)
    }
}

/** 自绘叹号（圆环 + 竖线 + 点），对应 KernelSU 的 ErrorOutline */
@Composable
private fun WarnGlyph(
    size: androidx.compose.ui.unit.Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = s * 0.09f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawCircle(color = color, radius = s * 0.42f, style = stroke)
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(s * 0.5f, s * 0.28f),
            end = androidx.compose.ui.geometry.Offset(s * 0.5f, s * 0.56f),
            strokeWidth = s * 0.09f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawCircle(color = color, radius = s * 0.055f, center = androidx.compose.ui.geometry.Offset(s * 0.5f, s * 0.72f))
    }
}

/**
 * 对应 KernelSU 的 InfoCard（两个卡片：系统信息 / 安全状态）。
 */
@Composable
private fun InfoCard(
    report: RootCheck.Report?,
    selinux: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoText("\u25C6", "管理器版本", "1.0")
                InfoText("\u25A3", "内核", System.getProperty("os.version") ?: "未知")
                InfoText("\u25A4", "设备型号", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
                InfoText("\u25CE", "指纹", android.os.Build.FINGERPRINT, bottomPadding = 0.dp)
            }
        }
        top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoText("\u25C7", "SELinux 状态", selinux)
                InfoText("\u2318", "su 路径", report?.suPath.orEmpty().ifBlank { "-" }, bottomPadding = 0.dp)
            }
        }
    }
}

/** 对应 KernelSU InfoCard 里的 InfoText */
@Composable
private fun InfoText(
    symbol: String,
    title: String,
    content: String,
    bottomPadding: androidx.compose.ui.unit.Dp = 24.dp,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = bottomPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        top.yukonga.miuix.kmp.basic.Text(
            text = symbol,
            fontSize = 20.sp,
            color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = 12.dp).width(24.dp),
        )
        Column {
            top.yukonga.miuix.kmp.basic.Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurface,
            )
            top.yukonga.miuix.kmp.basic.Text(
                text = content,
                fontSize = 13.sp,
                color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 对应 KernelSU 的 SupportLinks */
@Composable
private fun SupportLinks(modifier: Modifier = Modifier) {
    top.yukonga.miuix.kmp.basic.Card(modifier = modifier) {
        top.yukonga.miuix.kmp.basic.BasicComponent(
            title = "项目说明",
            summary = "AArch64 静态分析工具",
            startAction = {
                top.yukonga.miuix.kmp.basic.Text(
                    text = "\u2630",
                    fontSize = 20.sp,
                    color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(end = 6.dp),
                )
            },
        )
        top.yukonga.miuix.kmp.basic.BasicComponent(
            title = "Root 权限",
            summary = "基于 su 的真实授权检测",
            startAction = {
                top.yukonga.miuix.kmp.basic.Text(
                    text = "\u26E8",
                    fontSize = 20.sp,
                    color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(end = 6.dp),
                )
            },
        )
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

/** 选项行（带勾选） */
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

/** 开关行（点击整行切换） */
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

/**
 * 主题设置页。
 *
 * 对应 KernelSU 主题页的核心项：
 *   · 浅色 / 深色 / 跟随系统
 *   · 背景模糊 / 浮动底栏 / 玻璃效果 / 导航徽标
 *   · 界面缩放（与底栏设置合并在这里，与 KernelSU 一致）
 */
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
