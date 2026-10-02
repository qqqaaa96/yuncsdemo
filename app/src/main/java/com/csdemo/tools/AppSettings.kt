package com.csdemo.tools

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/**
 * 应用设置（主题 / 底栏 / 缩放）。
 *
 * 存储：SharedPreferences（轻量、无额外依赖）。
 * 读取：通过 Compose 的 mutableStateOf，变更后自动重组。
 */
object AppSettings {

    /** 主题模式：跟随系统 / 浅色 / 深色 */
    enum class ThemeMode(val label: String, val sub: String) {
        System("跟随系统", "随系统深色开关自动切换"),
        Light("浅色", "始终使用浅色界面"),
        Dark("深色", "始终使用深色界面"),
    }

    private const val PREF = "csdemo_settings"
    private const val K_THEME = "theme_mode"
    private const val K_FLOATING = "bottom_floating"
    private const val K_GLASS = "bottom_glass"
    private const val K_BLUR = "enable_blur"
    private const val K_BADGE = "nav_badge"
    private const val K_SCALE = "page_scale"

    private var prefs: SharedPreferences? = null

    // ---- 全局状态 ----
    val themeMode = mutableStateOf(ThemeMode.System)
    val floatingBottomBar = mutableStateOf(true)
    val glassBottomBar = mutableStateOf(true)
    val enableBlur = mutableStateOf(true)
    val navigationBadge = mutableStateOf(true)
    val pageScale = mutableStateOf(1.0f)

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs = p
        themeMode.value = runCatching { ThemeMode.valueOf(p.getString(K_THEME, ThemeMode.System.name) ?: ThemeMode.System.name) }
            .getOrDefault(ThemeMode.System)
        floatingBottomBar.value = p.getBoolean(K_FLOATING, true)
        glassBottomBar.value = p.getBoolean(K_GLASS, true)
        enableBlur.value = p.getBoolean(K_BLUR, true)
        navigationBadge.value = p.getBoolean(K_BADGE, true)
        pageScale.value = p.getFloat(K_SCALE, 1.0f)
    }

    fun setThemeMode(mode: ThemeMode) {
        themeMode.value = mode
        prefs?.edit()?.putString(K_THEME, mode.name)?.apply()
    }

    fun setFloatingBottomBar(v: Boolean) {
        floatingBottomBar.value = v
        prefs?.edit()?.putBoolean(K_FLOATING, v)?.apply()
    }

    fun setGlassBottomBar(v: Boolean) {
        glassBottomBar.value = v
        prefs?.edit()?.putBoolean(K_GLASS, v)?.apply()
    }

    fun setEnableBlur(v: Boolean) {
        enableBlur.value = v
        prefs?.edit()?.putBoolean(K_BLUR, v)?.apply()
    }

    fun setNavigationBadge(v: Boolean) {
        navigationBadge.value = v
        prefs?.edit()?.putBoolean(K_BADGE, v)?.apply()
    }

    fun setPageScale(v: Float) {
        val c = v.coerceIn(0.8f, 1.1f)
        pageScale.value = c
        prefs?.edit()?.putFloat(K_SCALE, c)?.apply()
    }
}
