package com.csdemo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Paper,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperSoft,
    onSurfaceVariant = InkSoft,
    outline = Line,
    error = Bad
)

/**
 * 深色配色。
 *
 * 与浅色保持同一套蓝色强调（Accent），
 * 背景用近黑灰而不是纯黑，保证卡片层次可辨。
 */
private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Paper,
    background = Color(0xFF121212),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF1B1B1B),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFFA8A8A8),
    outline = Color(0xFF3A3A3A),
    error = Bad
)

@Composable
fun CsdemoTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalPalette provides if (darkTheme) DarkPalette else LightPalette
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = AppTypography,
            content = content
        )
    }
}

/**
 * 当前应用是否处于深色模式。
 *
 * 依据是“应用自己的主题设置”（AppSettings.themeMode），
 * 而不是系统的深浅。
 *
 * 这一点很关键：用户可以在应用内选“深色”，而系统仍为浅色，
 * 此时若用 isSystemInDarkTheme() 会导致部分组件（主页状态卡、关于页背景）
 * 仍按浅色渲染，即所谓“没适配深色”。
 *
 * 与 KernelSU 的 isInDarkTheme() 思路一致。
 */
@Composable
fun isAppInDark(): Boolean {
    val mode = com.csdemo.tools.AppSettings.themeMode.value
    return when (mode) {
        com.csdemo.tools.AppSettings.ThemeMode.Dark -> true
        com.csdemo.tools.AppSettings.ThemeMode.Light -> false
        com.csdemo.tools.AppSettings.ThemeMode.System -> androidx.compose.foundation.isSystemInDarkTheme()
    }
}

