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
//   · root 未授权 → ksuVersion 为 null → “未授权 Root”卡片
// ============================================================

@Composable
fun HomePage(isVisible: Boolean = true) {
    // 每次“进入主页”都重新检测 root（包括从其他页面切回来）。
    // key 用 isVisible：从 false → true 时重启 effect，触发重测。
    // RootState 内部有并发保护，旧结果保留，不会闪烁。
    androidx.compose.runtime.LaunchedEffect(isVisible) {
        if (isVisible) {
            com.csdemo.tools.RootState.detectOnEnter()
        }
    }

    // Shizuku（ADB shell 模式）处理：
    //   · 未授权 → 自动申请一次（弹 Shizuku 授权框）；
    //   · 已授权 → 后台绑定 UserService，确认真的能拿到 shell；
    //   · 未安装/未启动 Shizuku → 什么都不做，直接落到 USER 身份。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val rootReport = com.csdemo.tools.RootState.report.value
    androidx.compose.runtime.LaunchedEffect(isVisible, rootReport) {
        if (!isVisible || rootReport?.granted == true) return@LaunchedEffect
        try {
            if (com.csdemo.tools.AdbShell.needsRequest()) {
                com.csdemo.tools.AdbShell.requestPermission()
            } else if (com.csdemo.tools.AdbShell.granted()) {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.csdemo.tools.AdbShell.ensureService(ctx.applicationContext)
                }
                if (ok) {
                    com.csdemo.tools.RootState.mode.value = com.csdemo.tools.AdbShell.Mode.ADB_SHELL
                }
            }
        } catch (_: Throwable) {
        }
    }
    val report = com.csdemo.tools.RootState.report.value
    val selinux = com.csdemo.tools.RootState.selinux.value

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
        // 三态：ROOT（绿）/ ADB_SHELL（紫）/ USER（蓝）
        runMode = com.csdemo.tools.RootState.mode.value,
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
            com.csdemo.ui.FeatureIcon(
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

@Composable
fun SettingsPage(
    onCheckUpdate: () -> Unit,
    onTheme: () -> Unit,
    onAbout: () -> Unit,
    onBottomBarSettings: () -> Unit,
    onScale: () -> Unit,
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
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "主题设置",
                        summary = "深色 / 浅色 / 跟随系统",
                        onClick = onTheme,
                    )
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "底栏设置",
                        summary = "浮动 / 玻璃 / 界面缩放",
                        onClick = onBottomBarSettings,
                    )
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "界面缩放",
                        summary = "调整界面整体大小",
                        onClick = onScale,
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
            top.yukonga.miuix.kmp.basic.TopAppBar(
                title = "主题设置",
                navigationIcon = {
                    top.yukonga.miuix.kmp.basic.IconButton(onClick = onBack) {
                        top.yukonga.miuix.kmp.basic.Text(
                            text = "\u2039",
                            fontSize = 22.sp,
                            color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
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
                // 外观模式:TabRow（与 KernelSU 主题页一致）
                val themeItems = listOf(
                    androidx.compose.ui.res.stringResource(com.csdemo.R.string.settings_theme_mode_system),
                    androidx.compose.ui.res.stringResource(com.csdemo.R.string.settings_theme_mode_light),
                    androidx.compose.ui.res.stringResource(com.csdemo.R.string.settings_theme_mode_dark),
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

                // 显示开关（与 KernelSU 一致）
                KsuSectionCard {
                    top.yukonga.miuix.kmp.preference.SwitchPreference(
                        title = androidx.compose.ui.res.stringResource(com.csdemo.R.string.settings_enable_blur),
                        summary = androidx.compose.ui.res.stringResource(com.csdemo.R.string.settings_enable_blur_summary),
                        checked = s.enableBlur.value,
                        onCheckedChange = { s.setEnableBlur(it) },
                    )
                    top.yukonga.miuix.kmp.preference.SwitchPreference(
                        title = androidx.compose.ui.res.stringResource(com.csdemo.R.string.settings_floating_bottom_bar),
                        summary = androidx.compose.ui.res.stringResource(com.csdemo.R.string.settings_floating_bottom_bar_summary),
                        checked = s.floatingBottomBar.value,
                        onCheckedChange = { s.setFloatingBottomBar(it) },
                    )
                    top.yukonga.miuix.kmp.preference.SwitchPreference(
                        title = androidx.compose.ui.res.stringResource(com.csdemo.R.string.settings_enable_glass),
                        summary = androidx.compose.ui.res.stringResource(com.csdemo.R.string.settings_enable_glass_summary),
                        checked = s.glassBottomBar.value,
                        onCheckedChange = { s.setGlassBottomBar(it) },
                    )
                    top.yukonga.miuix.kmp.preference.SwitchPreference(
                        title = androidx.compose.ui.res.stringResource(com.csdemo.R.string.settings_navigation_badge),
                        summary = androidx.compose.ui.res.stringResource(com.csdemo.R.string.settings_navigation_badge_summary),
                        checked = s.navigationBadge.value,
                        onCheckedChange = { s.setNavigationBadge(it) },
                    )
                }

                // 界面缩放（与 KernelSU 一致：ArrowPreference + Slider）
                KsuSectionCard {
                    var sliderValue by androidx.compose.runtime.remember(s.pageScale.value) {
                        androidx.compose.runtime.mutableFloatStateOf(s.pageScale.value)
                    }
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = androidx.compose.ui.res.stringResource(com.csdemo.R.string.settings_page_scale),
                        summary = androidx.compose.ui.res.stringResource(com.csdemo.R.string.settings_page_scale_summary),
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
    top.yukonga.miuix.kmp.basic.Scaffold(
        topBar = {
            top.yukonga.miuix.kmp.basic.TopAppBar(
                title = "检查更新",
                navigationIcon = {
                    top.yukonga.miuix.kmp.basic.IconButton(onClick = onBack) {
                        top.yukonga.miuix.kmp.basic.Text(
                            text = "\u2039",
                            fontSize = 22.sp,
                            color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
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
                    top.yukonga.miuix.kmp.preference.ArrowPreference(title = "当前版本", summary = currentVersion, onClick = {})
                    top.yukonga.miuix.kmp.preference.ArrowPreference(title = "更新渠道", summary = "正式版", onClick = {})
                    top.yukonga.miuix.kmp.preference.ArrowPreference(title = "更新状态", summary = "已是最新", onClick = {})
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun AboutPage(version: String, onBack: () -> Unit) {
    top.yukonga.miuix.kmp.basic.Scaffold(
        topBar = {
            top.yukonga.miuix.kmp.basic.TopAppBar(
                title = "关于",
                navigationIcon = {
                    top.yukonga.miuix.kmp.basic.IconButton(onClick = onBack) {
                        top.yukonga.miuix.kmp.basic.Text(
                            text = "\u2039",
                            fontSize = 22.sp,
                            color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
        },
        popupHost = { },
    ) { innerPadding ->
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
                        painter = androidx.compose.ui.res.painterResource(com.csdemo.R.mipmap.ic_launcher_native),
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    top.yukonga.miuix.kmp.basic.Text(
                        text = androidx.compose.ui.res.stringResource(com.csdemo.R.string.app_name),
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
                KsuSectionCard {
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
                KsuSectionCard {
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
                KsuSectionCard {
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
