package com.csdemo.ui.nav

import androidx.compose.runtime.saveable.rememberSaveable
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

/** 创建返回栈（带保存恢复） */
@androidx.compose.runtime.Composable
fun rememberAppBackStack(vararg initial: AppRoute): NavBackStack =
    rememberNavBackStack(*initial)
