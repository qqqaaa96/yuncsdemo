package com.csdemo.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack

/**
 * 应用导航键（极简实现）。
 *
 * 设计：
 *   · 每个页面一个 Route 常量（data object），实现 NavKey；
 *   · 不引入 @Serializable / @Parcelize，保持依赖最小；
 *   · 需要带参数的页面用 data class（例如 FuncDetail(addr)）。
 */
sealed interface AppRoute : NavKey {
    data object Home : AppRoute
    data object Device : AppRoute
    data object Root : AppRoute
    data object Apps : AppRoute
    data object Ping : AppRoute
    data object Dns : AppRoute
    data object Port : AppRoute
    data object Http : AppRoute
    data object Lan : AppRoute
    data object Codec : AppRoute
    data object Cpu : AppRoute
    data object Sched : AppRoute
    data object Thermal : AppRoute
    data object ThreadOpt : AppRoute
    data object Spoof : AppRoute
    data object Selinux : AppRoute
    data object Elf : AppRoute
    data object Settings : AppRoute
    data object ThemeSettings : AppRoute
    data object CheckUpdate : AppRoute
    data object About : AppRoute
}

/**
 * 路由名（字符串） ↔ AppRoute 的映射。
 *
 * 保留原来的字符串路由接口，使得各页面里 onOpen("ping") 这类调用
 * 不用改动，只在导航层做一次转换。
 */
fun routeFromId(id: String): AppRoute? = when (id) {
    "home" -> AppRoute.Home
    "device" -> AppRoute.Device
    "root" -> AppRoute.Root
    "apps" -> AppRoute.Apps
    "ping" -> AppRoute.Ping
    "dns" -> AppRoute.Dns
    "port" -> AppRoute.Port
    "http" -> AppRoute.Http
    "lan" -> AppRoute.Lan
    "codec" -> AppRoute.Codec
    "cpu" -> AppRoute.Cpu
    "sched" -> AppRoute.Sched
    "thermal" -> AppRoute.Thermal
    "thread" -> AppRoute.ThreadOpt
    "spoof" -> AppRoute.Spoof
    "selinux" -> AppRoute.Selinux
    "elf" -> AppRoute.Elf
    "set_theme" -> AppRoute.ThemeSettings
    "set_update" -> AppRoute.CheckUpdate
    "set_about" -> AppRoute.About
    else -> null
}

/**
 * 导航器：持有返回栈，提供 push / pop。
 *
 * 与 KernelSU 同思路：转场由 miuix-nav 的 NavDisplay 处理，
 * 不再使用 Compose 的 AnimatedContent（后者在布局阶段动画，高刷下必然掉帧）。
 */
class AppNavigator(val backStack: NavBackStack) {
    fun push(route: AppRoute) {
        if (route !in backStack) backStack.add(route)
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }

    fun current(): AppRoute? = backStack.lastOrNull() as? AppRoute

    fun clearTo(route: AppRoute) {
        while (backStack.size > 1) backStack.removeLastOrNull()
        if (backStack.isEmpty()) backStack.add(route) else backStack[backStack.lastIndex] = route
    }
}

/** 创建导航器（带保存恢复） */
@Composable
fun rememberAppNavigator(start: AppRoute = AppRoute.Home): AppNavigator {
    val backStack = rememberNavBackStack<AppRoute>(start)
    return remember(backStack) { AppNavigator(backStack) }
}

val LocalAppNavigator = staticCompositionLocalOf<AppNavigator> {
    error("LocalAppNavigator not provided")
}
