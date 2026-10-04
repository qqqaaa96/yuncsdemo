package com.csdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.csdemo.tools.Plan
import com.csdemo.ui.Motion
import com.csdemo.ui.AppsScreen
import com.csdemo.ui.CardKeyScreen
import com.csdemo.ui.CodecScreen
import com.csdemo.ui.CpuFreqScreen
import com.csdemo.ui.DeviceScreen
import com.csdemo.ui.DnsScreen
import com.csdemo.ui.HomeScreen
import com.csdemo.ui.HttpScreen
import com.csdemo.ui.LanScreen
import com.csdemo.ui.MailVerifyScreen
import com.csdemo.ui.PingScreen
import com.csdemo.ui.PlanScreen
import com.csdemo.ui.PortScanScreen
import com.csdemo.ui.PrivacyScreen
import com.csdemo.ui.RootScreen
import com.csdemo.ui.ElfScreen
import com.csdemo.ui.AppShell
import com.csdemo.tools.AppSettings
import com.csdemo.ui.pages.AboutPage
import com.csdemo.ui.pages.CheckUpdatePage
import com.csdemo.ui.pages.ThemeSettingsPage
import com.csdemo.ui.SchedScreen
import com.csdemo.ui.SelinuxScreen
import com.csdemo.ui.SpoofScreen
import com.csdemo.ui.ThermalScreen
import com.csdemo.ui.ThreadOptScreen
import com.csdemo.ui.theme.Accent
import com.csdemo.ui.theme.CsdemoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Plan.load(this)
        com.csdemo.tools.CardKey.load(this)
        com.csdemo.tools.MailAuth.load(this)
        AppSettings.load(this)
        setContent {
            val dark = when (AppSettings.themeMode.value) {
                AppSettings.ThemeMode.Dark -> true
                AppSettings.ThemeMode.Light -> false
                AppSettings.ThemeMode.System -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            // 界面缩放：整体缩放 density，让所有 dp 尺寸跟着变。
            //
            // 关键：必须 remember，只在 pageScale 真正变化时重建 Density 对象。
            // 若每次重组都新建 Density 实例，LocalDensity 会被视为“变了”，
            // 导致全树（含 miuix blur / backdrop）反复重新测量而崩溃。
            val base = androidx.compose.ui.platform.LocalDensity.current
            val scale = AppSettings.pageScale.value
            val scaled = androidx.compose.runtime.remember(base, scale) {
                androidx.compose.ui.unit.Density(
                    density = base.density * scale,
                    fontScale = base.fontScale
                )
            }
            CsdemoTheme(darkTheme = dark) {
                // miuix 主题：一级设置，覆盖整个应用（包括 AppShell、所有子页面）。
                // 参照 KernelSU 的 MiuixKernelSUTheme：必须传 isDark，
                // 仅传 ColorSchemeMode 不够，miuix 不会知道当前是深色。
                val miuixController = androidx.compose.runtime.remember(dark) {
                    top.yukonga.miuix.kmp.theme.ThemeController(
                        if (dark) top.yukonga.miuix.kmp.theme.ColorSchemeMode.Dark
                        else top.yukonga.miuix.kmp.theme.ColorSchemeMode.Light,
                        isDark = dark,
                    )
                }
                top.yukonga.miuix.kmp.theme.MiuixTheme(controller = miuixController) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalDensity provides scaled
                    ) {
                        Surface(
                            Modifier.fillMaxSize(),
                            color = if (dark) Color(0xFF121212) else Color.White
                        ) {
                            AppRoot()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var route by remember { mutableStateOf("") }

    // 首启流程决定初始路由
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (route.isEmpty()) {
            // 邮箱验证：只有从未通过过才需要验证
            route = if (!com.csdemo.tools.MailAuth.verified.value) {
                "mailverify"
            } else {
                when {
                    !Plan.agreed.value -> "privacy"
                    Plan.plan.value == Plan.NONE -> "plan"
                    else -> "home"
                }
            }
        }
    }

    // ---- 路由历史栈 ----
    // 侧滑/返回键不再总是回主页，而是回“上一个页面”。
    val history = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    fun navTo(target: String) {
        if (target != route) {
            // 防止历史无限增长
            if (history.size > 32) history.removeAt(0)
            history.add(route)
            route = target
        }
    }
    fun popBack() {
        if (history.isNotEmpty()) {
            route = history.removeAt(history.size - 1)
        } else {
            route = "home"
        }
    }

    val isHome = route == "home"
    // 隐私、方案、卡密、邮箱页不允许返回键跳过
    val canBack = route.isNotEmpty() && !isHome &&
            route != "privacy" && route != "plan" &&
            route != "cardkey" && route != "mailverify"
    BackHandler(enabled = canBack) {
        popBack()
    }

    // 状态栏高度。
    //
    // 注意：WindowInsets.statusBars 与 asPaddingValues() 都是 composable 扩展，
    // 只能在 composable 上下文直接调用，不能放进 remember{} 的 lambda。
    // 它们的开销很小（Compose 内部已缓存），无需自己 remember。
    val statusTop = androidx.compose.foundation.layout.WindowInsets.statusBars
        .asPaddingValues().calculateTopPadding()
    val isHomeRoute = route == "home"

    Box(
        Modifier
            .fillMaxSize()
            .background(if (AppSettings.themeMode.value == AppSettings.ThemeMode.Dark) Color(0xFF121212) else Color.White)
    ) {
        // 页面切换动画：新页淡入 + 右侧滑入 + 轻微放大；旧页淡出 + 缩小。
        // 每个功能的进入/退出都走这套过渡，节奏统一。
        androidx.compose.animation.AnimatedContent(
            targetState = route,
            // 关键（性能）：
            // 1) 不在 AnimatedContent 上做带条件变化的 padding。
            //    否则路由一变，padding 变 → 整棵子树（新旧两页）重新测量，动画首帧必卡。
            //    改为把状态栏高度放到内容里（见下方 Box）。
            // 2) sizeTransform = null：完全关闭“容器尺寸动画”。
            //    AnimatedContent 默认会为新旧两页不同尺寸做插值动画，
            //    这会导致每一帧都重新布局与重绘，是切页卡顿的主因。
            transitionSpec = {
                // 关键（性能）：用 .using(SizeTransform(clip = false)) 关闭“容器尺寸动画”。
                // AnimatedContent 默认会为新旧两页不同尺寸做插值，
                // 导致动画每一帧都要重新测量与布局两棵子树 —— 这就是“进出页面卡一下”的根因。
                // SizeTransform(clip = false) 关闭尺寸变化动画，只保留内容的淡入/位移。
                (
                    androidx.compose.animation.fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(Motion.FAST)
                    ) + androidx.compose.animation.slideInHorizontally(
                        animationSpec = androidx.compose.animation.core.tween(Motion.NORMAL),
                        initialOffsetX = { full -> full / 10 }
                    )
                )
                    .togetherWith(
                        androidx.compose.animation.fadeOut(
                            animationSpec = androidx.compose.animation.core.tween(Motion.FAST)
                        )
                    )
                    .using(androidx.compose.animation.SizeTransform(clip = false))
            },
            // 说明：本版本 Compose 的 AnimatedContent 没有 sizeTransform 参数，
            // 无法在这里直接关闭“容器尺寸动画”。
            // 已通过“去掉外层条件 padding + 子页包一层固定尺寸 Box”
            // 来避免容器尺寸变化，达到同样目的。
            label = "route"
        ) { current ->
            // 每个页面包一层：只做静态 padding，不参与尺寸动画。
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = if (current == "home") 0.dp else statusTop)
            ) {
            when (current) {
            "" -> {}
            "mailverify" -> MailVerifyScreen(onSent = {
                // 邮件发送成功后进入验证码输入页
                route = "cardkey"
            })
            "cardkey" -> CardKeyScreen(onPassed = {
                // 验证码通过后，再按首启流程决定去向
                route = when {
                    !Plan.agreed.value -> "privacy"
                    Plan.plan.value == Plan.NONE -> "plan"
                    else -> "home"
                }
            })
            "privacy" -> PrivacyScreen(
                onAgree = {
                    Plan.setAgreed(ctx, true)
                    route = if (Plan.plan.value == Plan.NONE) "plan" else "home"
                },
                onExit = {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            )
            "plan" -> PlanScreen(onPicked = { route = "home" })
            "home" -> AppShell(
                onOpen = { navTo(it) },
                onSettingsAction = { action -> navTo("set_" + action) },
                selectedTab = com.csdemo.tools.RootState.mainTab.value,
                onTabChange = { com.csdemo.tools.RootState.mainTab.value = it },
            )
            "set_update" -> CheckUpdatePage(currentVersion = "1.0", onBack = { popBack() })
            // 主题设置、底栏设置、界面缩放均在主题页内（与 KernelSU 一致）
            "set_theme", "set_bottombar", "set_scale" -> ThemeSettingsPage(onBack = { popBack() })
            "set_about" -> AboutPage(version = "1.0", onBack = { popBack() })
            // 常用功能 / 工具里点进去的页面：顶部自带“‹ 标题”返回键
            "device" -> DeviceScreen(onBack = { popBack() })
            "root" -> RootScreen(onBack = { popBack() })
            "apps" -> AppsScreen(onBack = { popBack() })
            "ping" -> PingScreen(onBack = { popBack() })
            "dns" -> DnsScreen(onBack = { popBack() })
            "port" -> PortScanScreen(onBack = { popBack() })
            "http" -> HttpScreen(onBack = { popBack() })
            "lan" -> LanScreen(onBack = { popBack() })
            "codec" -> CodecScreen(onBack = { popBack() })
            "cpu" -> CpuFreqScreen(onBack = { popBack() })
            "sched" -> SchedScreen(onBack = { popBack() })
            "thermal" -> ThermalScreen(onBack = { popBack() })
            "thread" -> ThreadOptScreen(onBack = { popBack() })
            "spoof" -> SpoofScreen(onBack = { popBack() })
            "selinux" -> SelinuxScreen(onBack = { popBack() })
            // ELF 页面不改（它自己已有返回控件）
            "elf" -> ElfScreen()
            else -> AppShell(
                onOpen = { navTo(it) },
                onSettingsAction = { action -> navTo("set_" + action) },
                selectedTab = com.csdemo.tools.RootState.mainTab.value,
                onTabChange = { com.csdemo.tools.RootState.mainTab.value = it },
            )
            }
            }
        }

        if (canBack) {
            TextButton(
                onClick = { popBack() },
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
            ) {
                Text("返回", color = Accent)
            }
        }
    }
}
