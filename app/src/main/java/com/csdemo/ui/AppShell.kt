package com.csdemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.csdemo.ui.theme.Paper
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 4 个主页面。顺序与底栏项一一对应：
 *   0 主页 / 1 常用功能 / 2 工具 / 3 设置
 */
enum class MainTab(val label: String, val icon: ImageVector) {
    Home("主页", Icons.Rounded.Cottage),
    Features("常用功能", Icons.Rounded.Security),
    Tools("工具", Icons.Rounded.Extension),
    Settings("设置", Icons.Rounded.Settings),
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
) {
    MiuixTheme {
        val tabs = MainTab.entries
        val pagerState = rememberPagerState(pageCount = { tabs.size })
        val scope = rememberCoroutineScope()

        // 背景采样层：底栏与内容都挂在这一层上
        val backdrop = rememberLayerBackdrop {
            drawRect(Paper)
            drawContent()
        }

        var selected by remember { mutableStateOf(0) }
        var lockedName by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(pagerState.currentPage) {
            if (selected != pagerState.currentPage) selected = pagerState.currentPage
        }

        Box(Modifier.fillMaxSize().background(Paper)) {
            // 第 2 步：页面内容注册进 backdrop（否则底栏无法采样）
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .layerBackdrop(backdrop),
                beyondViewportPageCount = 0,
                overscrollEffect = null,
            ) { page ->
                when (page) {
                    0 -> HomePage()
                    1 -> FeaturesPage(onOpen = onOpen, onLocked = { lockedName = it })
                    2 -> ToolsPage(onOpen = onOpen, onLocked = { lockedName = it })
                    else -> SettingsPage(
                        onCheckUpdate = { onSettingsAction("update") },
                        onTheme = { onSettingsAction("theme") },
                        onAbout = { onSettingsAction("about") },
                        onBottomBarSettings = { onSettingsAction("bottombar") },
                        onScale = { onSettingsAction("scale") },
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
                FloatingBottomBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            start = if (floating) 28.dp else 0.dp,
                            end = if (floating) 28.dp else 0.dp,
                            bottom = if (floating) bottomPad else 0.dp
                        ),
                    selectedIndex = selected,
                    onSelected = { index ->
                        selected = index
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    backdrop = backdrop,
                    tabsCount = tabs.size,
                    // 只在开启“玻璃效果”时启用亚克力/折射，否则退化为实心
                    isBlurEnabled = glass,
                ) { activateTab ->
                    tabs.forEachIndexed { index, tab ->
                        FloatingBottomBarItem(
                            selected = selected == index,
                            onClick = { activateTab(index) },
                            // 与模板一致：每项最小宽度 76dp，避免文字被截断
                            modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                modifier = Modifier.width(22.dp)
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
}
