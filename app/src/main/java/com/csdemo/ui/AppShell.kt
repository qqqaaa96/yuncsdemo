package com.csdemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.tools.AppSettings
import com.csdemo.ui.bottombar.FloatingBottomBar
import com.csdemo.ui.bottombar.FloatingBottomBarItem
import com.csdemo.ui.pages.FeaturesPage
import com.csdemo.ui.pages.HomePage
import com.csdemo.ui.pages.SettingsPage
import com.csdemo.ui.pages.ToolsPage
import com.csdemo.ui.pressable
import com.csdemo.ui.theme.Accent
import com.csdemo.ui.theme.Paper
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 4 个主页面。顺序与底栏项一一对应：
 *   0 主页 / 1 常用功能 / 2 工具 / 3 设置
 */
enum class MainTab(val label: String, val symbol: String) {
    Home("主页", "\u2302"),
    Features("常用功能", "\u25C8"),
    Tools("工具", "\u2699"),
    Settings("设置", "\u2638"),
}

/**
 * 应用主壳：4 页 + 液态玻璃浮动底栏。
 *
 * 结构与 KernelSU manager 完全一致：
 *   1. rememberLayerBackdrop 创建背景采样层
 *   2. 页面内容通过 Modifier.layerBackdrop(backdrop) 注册进这一层
 *   3. 底栏从同一个 backdrop 采样，才能看到折射与模糊
 *   4. 底栏外层 Box(fillMaxWidth) 撑开，内层 FloatingBottomBar 自动拉满
 *
 * 如果漏掉第 2 步，backdrop 里没有内容，底栏就会退化成一团实心颜色。
 */
@Composable
fun AppShell(
    onOpen: (String) -> Unit,
    onSettingsAction: (String) -> Unit,
    /**
     * 当前选中的 Tab 索引。
     *
     * 提升为外部状态（由 MainActivity 持有），不能用局部 remember：
     * 因为进入子页（如“关于”）时 AppShell 会被销毁，
     * 局部 remember 会重置为 0（主页），返回时就“直接回主页”而不是回原来的 Tab。
     */
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
) {
    // 注意：这里不再包裹 MiuixTheme。
    // miuix 主题已在 MainActivity 顶层统一设置（带 isDark），
    // 子层再包一层会覆盖外层、导致深色不一致。
    val pal = com.csdemo.ui.theme.LocalPalette.current
    val tabs = MainTab.entries
    // 用外部传入的初始页初始化 Pager，重建后会回到原 Tab。
    val pagerState = rememberPagerState(
        initialPage = selectedTab.coerceIn(0, tabs.size - 1),
        pageCount = { tabs.size }
    )
    val scope = rememberCoroutineScope()

    // 背景采样层：底栏与内容都挂在这一层上
    val backdrop = rememberLayerBackdrop {
        drawRect(pal.paper)
        drawContent()
    }

        var lockedName by remember { mutableStateOf<String?>(null) }

        // Pager 变化 → 同步给外部状态
        LaunchedEffect(pagerState.currentPage) {
            if (selectedTab != pagerState.currentPage) onTabChange(pagerState.currentPage)
        }

        Box(Modifier.fillMaxSize().background(pal.paper)) {
            // 第 2 步：页面内容注册进 backdrop（否则底栏无法采样）
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    // 注意：这里不再统一加 windowInsetsPadding(WindowInsets.statusBars)。
                    //
                    // 原因：四个 tab 的顶栏处理方式不同：
                    //   · 主页（HomePagerMiuix）= Scaffold + TopAppBar，
                    //     它内部已经处理了状态栏；
                    //   · 设置（SettingsPage）    = Scaffold + TopAppBar，同样已处理；
                    //   · 常用功能 / 工具          = 纯 Column，没有顶栏，需要自己避让。
                    //
                    // 以前在这里统一加一层，导致主页与设置页
                    // “外层 padding + 顶栏自带 padding” 叠加，
                    // 顶部多出一块空白（白边）。
                    // 现在改为：有 Scaffold 的页面自己处理，
                    // 没有的（FeaturesPage / ToolsPage）自己加，
                    // 保证任何页面都只有一层。
                    .layerBackdrop(backdrop),
                // 保留所有页面实例：切页时不会销毁/重建，
                // 因此滚动位置、页面内部状态都不会丢（回到原位置）。
                beyondViewportPageCount = tabs.size - 1,
                overscrollEffect = null,
            ) { page ->
                when (page) {
                    // 把“当前是否选中主页”传进去：每次切回主页都会重新检测 root
                    0 -> HomePage(isVisible = selectedTab == 0)
                    1 -> FeaturesPage(onOpen = onOpen, onLocked = { lockedName = it })
                    2 -> ToolsPage(onOpen = onOpen, onLocked = { lockedName = it })
                    // 主题设置已合并底栏与缩放，所以设置页不再有单独入口。
                    else -> SettingsPage(
                        onCheckUpdate = { onSettingsAction("update") },
                        onTheme = { onSettingsAction("theme") },
                        onAbout = { onSettingsAction("about") },
                    )
                }
            }

            // 底栏参数由设置页控制（浮动 / 玻璃）
            val floating = AppSettings.floatingBottomBar.value
            val glass = AppSettings.glassBottomBar.value

            val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val bottomPad = if (bottomInset != 0.dp) 8.dp + bottomInset else 28.dp

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
            // ------------------------------------------------------------
            // 底栏两种形态（与 KernelSU 一致：是两个并列分支，不是同一组件的参数）
            //
            //   悬浮开 → FloatingBottomBar（液态玻璃浮动胶囊，原来的样子）
            //   悬浮关 → 贴底横条：贴底、全宽、直角、实心、各项均分
            // ------------------------------------------------------------
            if (!floating) {
                // 实心底色：跟随 miuix 主题的表面色（与 KernelSU 的 NavigationBar 用色一致）
                val barBg = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface
                val onBarInk = pal.ink
                val accentInk = Accent
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(barBg)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .height(60.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 注意：weight 是 RowScope 的扩展，
                    // 在 forEachIndexed 的 lambda 里 this 已变成非 RowScope，
                    // 直接写 Modifier.weight(1f) 会报 Unresolved reference。
                    // 所以先把 RowScope 存到局部变量\(barScope\)，再用它调 weight。
                    val barScope = this
                    tabs.forEachIndexed { index, tab ->
                        val selected = selectedTab == index
                        Column(
                            modifier = barScope.run { Modifier.weight(1f) }
                                .fillMaxHeight()
                                .pressable(pressedScale = 0.94f) {
                                    onTabChange(index)
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = tab.symbol,
                                fontSize = 18.sp,
                                lineHeight = 20.sp,
                                color = if (selected) accentInk else onBarInk,
                            )
                            Text(
                                text = tab.label,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                maxLines = 1,
                                softWrap = false,
                                color = if (selected) accentInk else onBarInk,
                            )
                        }
                    }
                }
            } else {
                FloatingBottomBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 28.dp, end = 28.dp, bottom = bottomPad),
                    selectedIndex = selectedTab,
                    onSelected = { index ->
                        onTabChange(index)
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    backdrop = backdrop,
                    tabsCount = tabs.size,
                    // 只在开启“玻璃效果”时启用亚克力/折射，否则退化为实心
                    isBlurEnabled = glass,
                ) { activateTab ->
                    tabs.forEachIndexed { index, tab ->
                        FloatingBottomBarItem(
                            selected = selectedTab == index,
                            onClick = { activateTab(index) },
                            // 与模板一致：每项最小宽度 76dp，避免文字被截断
                            modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                        ) {
                            Text(
                                text = tab.symbol,
                                fontSize = 18.sp,
                                lineHeight = 20.sp,
                            )
                            Text(
                                text = tab.label,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // Root 功能锁定提示
        val ln = lockedName
        if (ln != null) {
            AlertDialog(
                onDismissRequest = { lockedName = null },
                title = { Text("需要 Root 方案") },
                text = { Text("「" + ln + "」属于 Root 功能。你当前是基础方案，\n切换到 Root 方案后即可使用。") },
                confirmButton = {
                    TextButton(onClick = { lockedName = null }) { Text("知道了") }
                }
            )
        }
    }
