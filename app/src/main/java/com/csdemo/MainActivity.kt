package com.csdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.csdemo.ui.pages.AboutPage
import com.csdemo.ui.pages.BottomBarSettingsPage
import com.csdemo.ui.pages.CheckUpdatePage
import com.csdemo.ui.pages.ScalePage
import com.csdemo.ui.pages.ThemeMode
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
        setContent {
            CsdemoTheme {
                Surface(Modifier.fillMaxSize(), color = Color.White) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var route by remember { mutableStateOf("") }

    // 全局界面状态（主题 / 底栏 / 缩放）
    var themeMode by remember { mutableStateOf(ThemeMode.System) }
    var bottomFloating by remember { mutableStateOf(true) }
    var bottomBlur by remember { mutableStateOf(true) }
    var uiScale by remember { mutableStateOf(1.0f) }

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

    val isHome = route == "home"
    // 隐私、方案、卡密页不允许返回键跳过
    val canBack = route.isNotEmpty() && !isHome &&
            route != "privacy" && route != "plan" &&
            route != "cardkey" && route != "mailverify"
    BackHandler(enabled = canBack) {
        route = "home"
    }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        // 页面切换：淡入淡出 + 轻微位移，节奏统一
        androidx.compose.animation.AnimatedContent(
            targetState = route,
            transitionSpec = {
                androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(Motion.NORMAL)
                ) togetherWith androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(Motion.FAST)
                )
            },
            label = "route"
        ) { current ->
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
                onOpen = { route = it },
                onSettingsAction = { action -> route = "set_" + action },
            )
            "set_update" -> CheckUpdatePage(currentVersion = "1.0", onBack = { route = "home" })
            "set_theme" -> ThemeSettingsPage(
                mode = themeMode,
                onModeChange = { themeMode = it },
                onBack = { route = "home" }
            )
            "set_about" -> AboutPage(version = "1.0", onBack = { route = "home" })
            "set_bottombar" -> BottomBarSettingsPage(
                floating = bottomFloating,
                blurEnabled = bottomBlur,
                onFloatingChange = { bottomFloating = it },
                onBlurChange = { bottomBlur = it },
                onBack = { route = "home" }
            )
            "set_scale" -> ScalePage(
                scale = uiScale,
                onScaleChange = { uiScale = it },
                onBack = { route = "home" }
            )
            "device" -> DeviceScreen()
            "root" -> RootScreen()
            "apps" -> AppsScreen()
            "ping" -> PingScreen()
            "dns" -> DnsScreen()
            "port" -> PortScanScreen()
            "http" -> HttpScreen()
            "lan" -> LanScreen()
            "codec" -> CodecScreen()
            "cpu" -> CpuFreqScreen()
            "sched" -> SchedScreen()
            "thermal" -> ThermalScreen()
            "thread" -> ThreadOptScreen()
            "spoof" -> SpoofScreen()
            "selinux" -> SelinuxScreen()
            "elf" -> ElfScreen()
            else -> AppShell(
                onOpen = { route = it },
                onSettingsAction = { action -> route = "set_" + action },
            )
            }
        }

        if (canBack) {
            TextButton(
                onClick = { route = "home" },
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
            ) {
                Text("返回", color = Accent)
            }
        }
    }
}
