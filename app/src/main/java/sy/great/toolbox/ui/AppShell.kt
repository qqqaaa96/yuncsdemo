package sy.great.toolbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import sy.great.toolbox.tools.AppSettings
import sy.great.toolbox.ui.bottombar.FloatingBottomBar
import sy.great.toolbox.ui.bottombar.FloatingBottomBarItem
import sy.great.toolbox.ui.pages.FeaturesPage
import sy.great.toolbox.ui.pages.HomePage
import sy.great.toolbox.ui.pages.SettingsPage
import sy.great.toolbox.ui.pages.ProfilePage
import sy.great.toolbox.ui.pages.ToolsPage
import sy.great.toolbox.ui.pressable
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Paper
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 4 个主页面。顺序与底栏项一一对应：
 *   0 主页 / 1 常用功能 / 2 工具 / 3 设置
 */
enum class MainTab(val label: String, val glyph: String) {
    Home("主页", "home"),
    // 反作弊检测（需要 Root，放在主页与常用功能之间）
    AntiCheat("反作弊", "anticheat"),
    Features("常用功能", "features"),
    Tools("工具", "tools"),
    // 原「设置」并入本页（头像 / 账号 / 检查更新 / 主题 / 关于 / 退出登录）
    Profile("我的", "profile"),
}

/**
 * 贴底底栏里的单个 tab。
 *
 * 声明为 RowScope 的扩展，就是为了能直接调用 Modifier.weight：
 * weight 是 RowScope 的成员扩展，只能在“接收者就是 RowScope”的位置调用。
 * 若写成普通 Composable、在 forEachIndexed 的 lambda 里调用，
 * 那个位置的 this 已不是 RowScope，会报：
 *   cannot be called in this context with an implicit receiver
 *
 * 这也是下面 Row 里“逐个显式调用”而不是 forEachIndexed 的原因。
 */
@Composable
private fun RowScope.TabItem(
    glyph: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val pal = sy.great.toolbox.ui.theme.LocalPalette.current
    Column(
        // weight(1f)：各项严格均分。
        // 不能用 fillMaxWidth(0.25f) 代替：
        // fillMaxWidth 的参照物是“当前剩余宽度”，会越排越窄、右侧空出一大截。
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .pressable(pressedScale = 0.94f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 矢量图标（不用 Emoji）
        TabGlyph(
            kind = glyph,
            color = if (selected) Accent else pal.inkSoft,
            size = 22.dp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            softWrap = false,
            color = if (selected) Accent else pal.inkSoft,
        )
    }
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
    /**
     * 「我的」页里的退出登录。
     * 由 MainActivity 清除 token 并跳回登录页。
     */
    onLogout: () -> Unit = {},
    /** 「我的」→ 修改昵称 */
    onChangeUsername: () -> Unit = {},
    /** 「我的」→ 换绑邮箱 */
    onChangeEmail: () -> Unit = {},
    /** 「我的」→ 注销账号 */
    onDeleteAccount: () -> Unit = {},
    /** 「我的」→ 意见反馈 */
    onFeedback: () -> Unit = {},
) {
    // 注意：这里不再包裹 MiuixTheme。
    // miuix 主题已在 MainActivity 顶层统一设置（带 isDark），
    // 子层再包一层会覆盖外层、导致深色不一致。
    val pal = sy.great.toolbox.ui.theme.LocalPalette.current
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
                    // 索引与 MainTab 顺序一致：
                    // 0 主页 / 1 反作弊 / 2 常用功能 / 3 工具 / 4 我的
                    0 -> HomePage(isVisible = selectedTab == 0)
                    1 -> AntiCheatPage(
                        // 底部「阻断」按钮 → 跳到阻断页
                        onOpenBlock = { onOpen("anti_block") }
                    )
                    2 -> FeaturesPage(onOpen = onOpen, onLocked = { lockedName = it })
                    3 -> ToolsPage(onOpen = onOpen, onLocked = { lockedName = it })
                    // 「我的」已合并原设置页全部入口 + 头像 + 退出登录。
                    else -> ProfilePage(
                        onCheckUpdate = { onSettingsAction("update") },
                        onTheme = { onSettingsAction("theme") },
                        onAbout = { onSettingsAction("about") },
                        onLogout = { onLogout() },
                        onChangeUsername = onChangeUsername,
                        onChangeEmail = onChangeEmail,
                        onDeleteAccount = onDeleteAccount,
                        onFeedback = onFeedback,
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
            if (!floating) {
                // ---- 关闭悬浮：贴底横条（KernelSU 关闭悬浮后的形态）----
                // 贴底、全宽、直角、实心背景、各 tab 均分。
                val barBg = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(barBg)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .height(72.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 逐个显式调用：每个 TabItem 都直接位于 Row 的作用域内，
                    // 内部才能用 Modifier.weight(1f) 做严格均分。
                    // （写成 forEachIndexed 时 this 不再是 RowScope。）
                    val go: (Int) -> Unit = { i ->
                        onTabChange(i)
                        scope.launch { pagerState.animateScrollToPage(i) }
                    }
                    TabItem(
                        glyph = tabs[0].glyph,
                        label = tabs[0].label,
                        selected = selectedTab == 0,
                        onClick = { go(0) },
                    )
                    TabItem(
                        glyph = tabs[1].glyph,
                        label = tabs[1].label,
                        selected = selectedTab == 1,
                        onClick = { go(1) },
                    )
                    TabItem(
                        glyph = tabs[2].glyph,
                        label = tabs[2].label,
                        selected = selectedTab == 2,
                        onClick = { go(2) },
                    )
                    TabItem(
                        glyph = tabs[3].glyph,
                        label = tabs[3].label,
                        selected = selectedTab == 3,
                        onClick = { go(3) },
                    )
                    // 第 5 项（设置）。新增 tab 时必须同步补在这里，
                    // 否则关闭悬浮底栏后会少一项。
                    if (tabs.size > 4) {
                        TabItem(
                            glyph = tabs[4].glyph,
                            label = tabs[4].label,
                            selected = selectedTab == 4,
                            onClick = { go(4) },
                        )
                    }
                }
            } else {
                // ---- 开启悬浮：原来的液态玻璃浮动胶囊（一字未改）----
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
                            TabGlyph(
                                kind = tab.glyph,
                                color = if (selectedTab == index) Accent
                                else sy.great.toolbox.ui.theme.LocalPalette.current.inkSoft,
                                size = 22.dp,
                            )
                            Spacer(Modifier.height(2.dp))
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
}
