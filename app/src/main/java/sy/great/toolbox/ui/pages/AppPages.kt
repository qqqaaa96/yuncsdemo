package sy.great.toolbox.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CallToAction
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
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
import sy.great.toolbox.tools.AppSettings
import sy.great.toolbox.tools.Plan
import sy.great.toolbox.tools.RootCheck
import sy.great.toolbox.ui.FEATURES
import sy.great.toolbox.ui.Feature
import sy.great.toolbox.ui.HLine
import sy.great.toolbox.ui.RowItem
import sy.great.toolbox.ui.pressable
import sy.great.toolbox.ui.staggerIn
import sy.great.toolbox.ui.theme.LocalPalette
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

/**
 * 四个主页面。
 *
 * 主页（页面1）调用 sy.great.toolbox.ksu.HomePagerMiuix，即 KernelSU 主页 UI 的
 * 完整复制；其余页面沿用项目原有 UI 风格。
 */

// ============================================================
// 页面 1：主页 —— KernelSU 主页 UI（完整复制）
//
// 数据来源换成本项目的 RootCheck / Selinux：
//   · root 已授权 → ksuVersion 非空 → 绿色卡片（文字：“授权 su 成功”）
//   · root 未授权 → ksuVersion 为 null → “未授权 Root”卡片
// ============================================================

@Composable
fun HomePage(isVisible: Boolean = true) {
    // 每次“进入主页”都重新检测 root（包括从其他页面切回来）。
    // key 用 isVisible：从 false → true 时重启 effect，触发重测。
    // RootState 内部有并发保护，旧结果保留，不会闪烁。
    androidx.compose.runtime.LaunchedEffect(isVisible) {
        if (isVisible) {
            sy.great.toolbox.tools.RootState.detectOnEnter()
        }
    }

    // Shizuku（ADB shell 模式）处理：
    //   · 未授权 → 自动申请一次（弹 Shizuku 授权框）；
    //   · 已授权 → 后台绑定 UserService，确认真的能拿到 shell；
    //   · 未安装/未启动 Shizuku → 什么都不做，直接落到 USER 身份。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val rootReport = sy.great.toolbox.tools.RootState.report.value
    androidx.compose.runtime.LaunchedEffect(isVisible, rootReport) {
        if (!isVisible || rootReport?.granted == true) return@LaunchedEffect
        try {
            if (sy.great.toolbox.tools.AdbShell.needsRequest()) {
                sy.great.toolbox.tools.AdbShell.requestPermission()
            } else if (sy.great.toolbox.tools.AdbShell.granted()) {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    sy.great.toolbox.tools.AdbShell.ensureService(ctx.applicationContext)
                }
                if (ok) {
                    sy.great.toolbox.tools.RootState.mode.value = sy.great.toolbox.tools.AdbShell.Mode.ADB_SHELL
                }
            }
        } catch (_: Throwable) {
        }
    }
    val report = sy.great.toolbox.tools.RootState.report.value
    val selinux = sy.great.toolbox.tools.RootState.selinux.value

    val granted = report?.granted == true
    val state = sy.great.toolbox.ksu.HomeUiState(
        kernelVersion = sy.great.toolbox.ksu.parseKernelVersion(System.getProperty("os.version") ?: ""),
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
        latestVersionInfo = sy.great.toolbox.ksu.LatestVersionInfo(),
        currentManagerVersionCode = 1L,
        systemInfo = sy.great.toolbox.ksu.SystemInfo(
            kernelVersion = System.getProperty("os.version") ?: "未知",
            // 直接读安装包，不再手写
            managerVersion = sy.great.toolbox.tools.AppVersion.name(ctx).ifBlank { "未知" },
            deviceModel = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
            fingerprint = android.os.Build.FINGERPRINT,
            selinuxStatus = selinux,
            seccompStatus = 2,
        ),
    )
    val actions = sy.great.toolbox.ksu.HomeActions(
        // 点击状态卡片：重新检测身份（root / adb / user）。
        // 若 Shizuku 可用但未授权，会顺带弹出授权框。
        onInstallClick = {
            sy.great.toolbox.tools.RootState.refresh()
            try {
                sy.great.toolbox.tools.AdbShell.requestPermission()
            } catch (_: Throwable) {
            }
        },
        // 关注开发者：用系统浏览器/应用打开链接
        onOpenUrl = { url ->
            try {
                val i = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch (_: Throwable) {
            }
        },
    )
    sy.great.toolbox.ksu.HomePagerMiuix(
        state = state,
        actions = actions,
        bottomInnerPadding = 140.dp,
        // 三态：ROOT（绿）/ ADB_SHELL（紫）/ USER（蓝）
        runMode = sy.great.toolbox.tools.RootState.mode.value,
        // 双能力：同时具备 adb 与 root 时显示双色卡片
        hasAdb = try { sy.great.toolbox.tools.AdbShell.granted() } catch (_: Throwable) { false },
        hasRoot = granted,
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
            // 绘制图标（不用 Emoji / 字符）
            sy.great.toolbox.ui.FeatureIcon(
                id = f.id,
                color = iconColor,
                size = 22.dp,
            )
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
    // 本页没有 Scaffold / TopAppBar（只有自己画的标题），
    // 需要自己避开状态栏；否则内容会顶到状态栏下面。
    // AppShell 不再统一加这层 padding（否则有 TopAppBar 的页面会出现白边）。
    Column(
        Modifier
            .fillMaxSize()
            .background(pal.paper)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
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
    // 同 FeaturesPage：本页没有 TopAppBar，自己避开状态栏。
    Column(
        Modifier
            .fillMaxSize()
            .background(pal.paper)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
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

// ============================================================
// 设置（KernelSU 风格：miuix Scaffold + Card + Preference 组件）
// ============================================================

@Composable
private fun KsuSectionCard(content: @Composable () -> Unit) {
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
        content = { content() },
    )
}

/**
 * 玻璃卡片（从 KernelSU 关于页复制）。
 *
 * 关键点：
 *   · 卡片背景色为 Color.Transparent（不挡背景）；
 *   · 用 textureBlur 采样 backdrop，得到半透明磨砂玻璃；
 *   · contentColor 也设为透明，使内部文字/图标“随背景颜色流动”。
 */
@Composable
private fun GlassCard(
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop,
    blendColors: List<top.yukonga.miuix.kmp.blur.BlendColorEntry>,
    content: @Composable () -> Unit,
) {
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .then(
                Modifier.textureBlur(
                    backdrop = backdrop,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    blurRadius = 60f,
                    colors = top.yukonga.miuix.kmp.blur.BlurColors(blendColors = blendColors),
                    enabled = true,
                )
            ),
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
            Color.Transparent,
            Color.Transparent,
        ),
        content = { content() },
    )
}

@Composable
fun SettingsPage(
    onCheckUpdate: () -> Unit,
    onTheme: () -> Unit,
    onAbout: () -> Unit,
) {
    top.yukonga.miuix.kmp.basic.Scaffold(
        topBar = {
            top.yukonga.miuix.kmp.basic.TopAppBar(title = "设置")
        },
        popupHost = { },
    ) { innerPadding ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
            overscrollEffect = null,
        ) {
            item {
                KsuSectionCard {
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "检查更新",
                        summary = "查看是否有新版本",
                        onClick = onCheckUpdate,
                    )
                    // 主题设置已经包含“外观模式 + 底栏（浮动/玻璃/徽章）+ 界面缩放”，
                    // 因此不再单独列“底栏设置”和“界面缩放”：
                    // 三个入口本来就指向同一个页面，分开列只会让用户困惑。
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "主题设置",
                        summary = "外观 / 底栏 / 界面缩放",
                        onClick = onTheme,
                    )
                }

                KsuSectionCard {
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "关于",
                        summary = "版本 / 类型",
                        onClick = onAbout,
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun ThemeSettingsPage(onBack: () -> Unit) {
    val s = AppSettings
    top.yukonga.miuix.kmp.basic.Scaffold(
        topBar = {
            // 与其它子页同款：蓝色“〈” + 标题。
            //
            // 不再用 miuix TopAppBar：
            //   · 它内部会自己处理状态栏，与 MainActivity 的外层 padding 叠加，
            //     会在页面顶部多出一块空白（白边）；
            //   · 且它没有返回键，用户无法退回上一层。
            sy.great.toolbox.ui.PageHeader("主题设置", onBack)
        },
        // 必需：OverlayDropdownPreference 的下拉弹窗靠 Scaffold 的 popupHost 承载。
        // 缺了它，点击下拉时会因为没有宿主而崩溃。
        popupHost = { },
    ) { innerPadding ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
            overscrollEffect = null,
        ) {
            item {
                // 顶部留白：与状态栏拉开距离，避免内容贴顶（只在本页生效）
                Spacer(Modifier.height(16.dp))

                // ---- 预览卡（复刻 KernelSU 主题页顶部预览）----
                // 用当前真实配色画一个小预览：底栏形状 + 强调色，
                // 让用户在拖动开关前就能看到效果。
                val pal = LocalPalette.current
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 4.dp)
                        .background(pal.paperSoft, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                        .padding(16.dp),
                ) {
                    Text(
                        "界面预览",
                        fontSize = 13.sp, color = pal.inkSoft
                    )
                    Spacer(Modifier.height(12.dp))
                    // 模拟一条底栏：浮动（左右缩进+圆角）或贴合
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (s.floatingBottomBar.value)
                                    Modifier.padding(horizontal = 18.dp)
                                else Modifier
                            )
                            .height(44.dp)
                            .background(
                                if (s.floatingBottomBar.value) pal.paper else pal.paperSoft,
                                androidx.compose.foundation.shape.RoundedCornerShape(
                                    if (s.floatingBottomBar.value) 22.dp else 0.dp
                                )
                            )
                            .then(
                                if (s.floatingBottomBar.value)
                                    Modifier.border(
                                        1.dp, pal.line,
                                        androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
                                    )
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (s.floatingBottomBar.value) "浮动底栏" else "贴合底栏",
                            fontSize = 12.sp, color = pal.inkSoft
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 外观模式:TabRow（与 KernelSU 主题页一致）
                val themeItems = listOf(
                    androidx.compose.ui.res.stringResource(sy.great.toolbox.R.string.settings_theme_mode_system),
                    androidx.compose.ui.res.stringResource(sy.great.toolbox.R.string.settings_theme_mode_light),
                    androidx.compose.ui.res.stringResource(sy.great.toolbox.R.string.settings_theme_mode_dark),
                )
                top.yukonga.miuix.kmp.basic.TabRow(
                    tabs = themeItems,
                    selectedTabIndex = s.themeMode.value.ordinal.coerceIn(0, 2),
                    onTabSelected = { index ->
                        s.setThemeMode(
                            when (index) {
                                1 -> AppSettings.ThemeMode.Light
                                2 -> AppSettings.ThemeMode.Dark
                                else -> AppSettings.ThemeMode.System
                            }
                        )
                    },
                )

                // 显示开关（复刻 KernelSU：每项左侧带图标）
                KsuSectionCard {
                    top.yukonga.miuix.kmp.preference.SwitchPreference(
                        title = androidx.compose.ui.res.stringResource(sy.great.toolbox.R.string.settings_enable_blur),
                        summary = androidx.compose.ui.res.stringResource(sy.great.toolbox.R.string.settings_enable_blur_summary),
                        startAction = {
                            Icon(
                                Icons.Rounded.BlurOn,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                                tint = pal.inkSoft,
                            )
                        },
                        checked = s.enableBlur.value,
                        onCheckedChange = { s.setEnableBlur(it) },
                    )
                    top.yukonga.miuix.kmp.preference.SwitchPreference(
                        title = androidx.compose.ui.res.stringResource(sy.great.toolbox.R.string.settings_floating_bottom_bar),
                        summary = androidx.compose.ui.res.stringResource(sy.great.toolbox.R.string.settings_floating_bottom_bar_summary),
                        startAction = {
                            Icon(
                                Icons.Rounded.CallToAction,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                                tint = pal.inkSoft,
                            )
                        },
                        checked = s.floatingBottomBar.value,
                        onCheckedChange = { s.setFloatingBottomBar(it) },
                    )
                    top.yukonga.miuix.kmp.preference.SwitchPreference(
                        title = androidx.compose.ui.res.stringResource(sy.great.toolbox.R.string.settings_enable_glass),
                        summary = androidx.compose.ui.res.stringResource(sy.great.toolbox.R.string.settings_enable_glass_summary),
                        startAction = {
                            Icon(
                                Icons.Rounded.WaterDrop,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                                tint = pal.inkSoft,
                            )
                        },
                        checked = s.glassBottomBar.value,
                        onCheckedChange = { s.setGlassBottomBar(it) },
                    )
                    // 「导航栏角标」已按要求移除。
                }

                Spacer(Modifier.height(12.dp))

                // 界面缩放（与 KernelSU 一致：ArrowPreference + Slider，左侧带图标）
                KsuSectionCard {
                    var sliderValue by androidx.compose.runtime.remember(s.pageScale.value) {
                        androidx.compose.runtime.mutableFloatStateOf(s.pageScale.value)
                    }
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = androidx.compose.ui.res.stringResource(sy.great.toolbox.R.string.settings_page_scale),
                        summary = androidx.compose.ui.res.stringResource(sy.great.toolbox.R.string.settings_page_scale_summary),
                        startAction = {
                            Icon(
                                Icons.Rounded.AspectRatio,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                                tint = LocalPalette.current.inkSoft,
                            )
                        },
                        endActions = {
                            top.yukonga.miuix.kmp.basic.Text(
                                text = "${(sliderValue * 100).toInt()}%",
                            )
                        },
                        onClick = { },
                        bottomAction = {
                            top.yukonga.miuix.kmp.basic.Slider(
                                value = sliderValue,
                                onValueChange = { sliderValue = it },
                                onValueChangeFinished = {
                                    // 松手才真正应用，避免拖动中反复重建 Density
                                    s.setPageScale(sliderValue)
                                },
                                valueRange = 0.8f..1.1f,
                                showKeyPoints = true,
                                keyPoints = listOf(0.8f, 0.9f, 1f, 1.1f),
                                magnetThreshold = 0.01f,
                            )
                        },
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun CheckUpdatePage(currentVersion: String, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current

    var info by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<sy.great.toolbox.tools.VersionInfo.Info?>(null)
    }
    var loading by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(true)
    }
    // 是否弹更新日志
    var showChangelog by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        loading = true
        info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            sy.great.toolbox.tools.VersionInfo.fetch()
        }
        loading = false
    }

    val curCode = sy.great.toolbox.tools.AppVersion.code(ctx)
    val latest = info
    val stateText = when {
        loading -> "检查中..."
        latest == null || !latest.ok -> "检查失败：" + (latest?.error ?: "未知")
        latest.versionCode > curCode -> "有新版本 " + latest.versionName
        else -> "已是最新"
    }

    top.yukonga.miuix.kmp.basic.Scaffold(
        topBar = {
            // 与其它子页同款：蓝色“〈” + 标题（不叠加状态栏，避免顶部白边）
            sy.great.toolbox.ui.PageHeader("检查更新", onBack)
        },
        popupHost = { },
    ) { innerPadding ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
            overscrollEffect = null,
        ) {
            item {
                // 顶部留白：与状态栏拉开距离，避免内容贴顶
                Spacer(Modifier.height(16.dp))
                KsuSectionCard {
                    top.yukonga.miuix.kmp.preference.ArrowPreference(title = "当前版本", summary = currentVersion, onClick = {})
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "最新版本",
                        summary = if (latest?.ok == true && latest.versionName.isNotBlank())
                            latest.versionName else "—",
                        onClick = {}
                    )
                    top.yukonga.miuix.kmp.preference.ArrowPreference(title = "更新状态", summary = stateText, onClick = {})
                    // 有日志时可直接点开（与 KernelSU 一致：先看日志再更新）
                    if (latest?.ok == true && latest.changelog.isNotEmpty()) {
                        top.yukonga.miuix.kmp.preference.ArrowPreference(
                            title = "更新日志",
                            summary = latest.changelog.size.toString() + " 条变更",
                            onClick = { showChangelog = true }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                // 前往下载
                if (latest?.ok == true && latest.versionCode > curCode && latest.downloadUrl.isNotBlank()) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .padding(horizontal = 4.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                                .background(sy.great.toolbox.ui.theme.Accent)
                                .androidx.compose.foundation.clickable {
                                    try {
                                        ctx.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(latest.downloadUrl)
                                            )
                                        )
                                    } catch (_: Throwable) {}
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.Text(
                                "前往下载",
                                fontSize = 14.sp,
                                color = androidx.compose.ui.graphics.Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    // 更新日志弹窗（KernelSU 同款）
    if (showChangelog && latest?.ok == true) {
        sy.great.toolbox.ui.component.ChangelogDialog(
            versionName = latest.versionName,
            changelog = latest.changelog.joinToString("\n") { "- " + it },
            onDismiss = { showChangelog = false }
        )
    }
}

@Composable
fun AboutPage(version: String, onBack: () -> Unit) {
    val isInDark = androidx.compose.foundation.isSystemInDarkTheme()
    // 背景采样层：动态色块背景注册到它上面，卡片再从这里采样。
    // 这是 KernelSU 关于页“半透明卡片 + 文字/图标跟随背景流动”的关键。
    val backdrop = top.yukonga.miuix.kmp.blur.rememberLayerBackdrop()
    val blendColors = androidx.compose.runtime.remember(isInDark) {
        if (isInDark) sy.great.toolbox.ksu.effect.ColorBlendToken.Overlay_Thin_Light
        else sy.great.toolbox.ksu.effect.ColorBlendToken.Pured_Regular_Light
    }

    top.yukonga.miuix.kmp.basic.Scaffold(
        topBar = {
            // 关于页的顶栏必须保持透明：
            // 动态色块背景（BgEffectBackground）要从顶栏区域透上来，
            // 换成 PageHeader 会把背景遮住，破坏玻璃效果。
            //
            // 所以这里不改 TopAppBar，只在它上层叠一个返回箭头：
            // TopAppBar 自身布局、标题位置、透明背景全部不变。
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
                top.yukonga.miuix.kmp.basic.TopAppBar(
                    title = "关于",
                    color = Color.Transparent,
                )
                // 返回键：与其它子页同款的蓝色“〈”，靠左垂直居中。
                sy.great.toolbox.ui.BackArrow(
                    onClick = onBack,
                    tint = sy.great.toolbox.ui.theme.Accent,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        },
        popupHost = { },
    ) { innerPadding ->
        // 背景：KernelSU 关于页的动态色块背景（AGSL 着色器）。
        // bgModifier = layerBackdrop(backdrop)：把背景注册到 backdrop，
        // 卡片用 textureBlur 采样它，就得到半透明玻璃 + 内容随背景流动的效果。
        sy.great.toolbox.ksu.effect.BgEffectBackground(
            dynamicBackground = true,
            modifier = Modifier.fillMaxSize(),
            bgModifier = Modifier.layerBackdrop(backdrop),
            isFullSize = true,
            effectBackground = true,
        ) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
            overscrollEffect = null,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- 头部：Logo + 应用名 + 版本（与 KernelSU 关于页结构一致）----
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 直接用 mipmap 里的 PNG，不用 adaptive-icon 的 inset 包装
                    // （painterResource 对 <inset> 嵌套 mipmap 支持不完整，会抛异常）。
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(sy.great.toolbox.R.mipmap.ic_launcher_native),
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    top.yukonga.miuix.kmp.basic.Text(
                        text = androidx.compose.ui.res.stringResource(sy.great.toolbox.R.string.app_name),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(4.dp))
                    top.yukonga.miuix.kmp.basic.Text(
                        text = version,
                        fontSize = 13.sp,
                        color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            // ---- 应用信息 ----
            item {
                GlassCard(backdrop = backdrop, blendColors = blendColors) {
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "应用类型",
                        summary = "Android 原生工具箱",
                        onClick = {},
                    )
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "主要功能",
                        summary = "设备检测 · 网络工具 · Root · ELF 逆向",
                        onClick = {},
                    )
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "界面",
                        summary = "液态玻璃底栏 · miuix-kmp",
                        onClick = {},
                    )
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "运行环境",
                        summary = "Android 13 及以上",
                        onClick = {},
                    )
                }
            }

            // ---- 设备信息 ----
            item {
                GlassCard(backdrop = backdrop, blendColors = blendColors) {
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "设备型号",
                        summary = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
                        onClick = {},
                    )
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "系统版本",
                        summary = "Android " + android.os.Build.VERSION.RELEASE,
                        onClick = {},
                    )
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "内核版本",
                        summary = System.getProperty("os.version") ?: "未知",
                        onClick = {},
                    )
                }
            }

            // ---- 开源许可 ----
            item {
                GlassCard(backdrop = backdrop, blendColors = blendColors) {
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "开源许可",
                        summary = "miuix-kmp（Apache-2.0）· 液态玻璃效果",
                        onClick = {},
                    )
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "参考项目",
                        summary = "KernelSU · AndroidLiquidGlass",
                        onClick = {},
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
        }
    }
}
