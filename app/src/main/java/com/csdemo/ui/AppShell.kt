package com.csdemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.csdemo.ui.bottombar.FloatingBottomBar
import com.csdemo.ui.bottombar.FloatingBottomBarItem
import com.csdemo.ui.pages.FeaturesPage
import com.csdemo.ui.pages.HomePage
import com.csdemo.ui.pages.SettingsPage
import com.csdemo.ui.pages.ToolsPage
import com.csdemo.ui.theme.Paper
import kotlinx.coroutines.launch
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
 * 应用主壳：4 页 + 液态玻璃底栏（100% 复刻模板）。
 *
 * @param onOpen   子页面路由回调（沿用项目原有 route 机制）
 * @param onSettingsAction 设置页子项回调（update / theme / about / bottombar / scale）
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

        // 内容背景 backdrop：底栏从这里采样做液态折射
        val backdrop = rememberLayerBackdrop {
            drawRect(Color.White)
            drawContent()
        }

        var selected by remember { mutableStateOf(0) }
        var lockedName by remember { mutableStateOf<String?>(null) }

        // pager 与底栏选中态双向同步
        LaunchedEffect(pagerState.currentPage) {
            if (selected != pagerState.currentPage) selected = pagerState.currentPage
        }

        Box(Modifier.fillMaxSize().background(Paper)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 0,
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

            // 浮动液态玻璃底栏（长度与模板一致：左右各 28dp 留白）
            val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val bottomPad = if (bottomInset != 0.dp) 8.dp + bottomInset else 28.dp

            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 28.dp, end = 28.dp, bottom = bottomPad)
            ) {
                FloatingBottomBar(
                    selectedIndex = selected,
                    onSelected = { index ->
                        selected = index
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    backdrop = backdrop,
                    tabsCount = tabs.size,
                    isBlurEnabled = true,
                ) { activateTab ->
                    tabs.forEachIndexed { index, tab ->
                        FloatingBottomBarItem(
                            selected = selected == index,
                            onClick = { activateTab(index) },
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

        // Root 功能锁定提示（与原 Home.kt 行为一致）
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
