package com.csdemo.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// 浅色基础色（保持原值不变，旧页面继续直接引用这些常量）
// ---------------------------------------------------------------------------

val Ink = Color(0xFF111111)
val InkSoft = Color(0xFF666666)
val InkFaint = Color(0xFF9A9A9A)
val Line = Color(0xFFE6E6E6)
val Paper = Color(0xFFFFFFFF)
val PaperSoft = Color(0xFFF6F6F6)
val Accent = Color(0xFF1B6EF3)
val Warn = Color(0xFFD97706)
val Bad = Color(0xFFCC2222)
val Good = Color(0xFF17803D)

// ---------------------------------------------------------------------------
// 可跟随主题切换的色板
//
// 新 UI（底栏 + 4 个主页面）从 LocalPalette 取色，
// 这样切深色时它们会跟着变；旧页面继续用上面的固定常量，不受影响。
// ---------------------------------------------------------------------------

@Immutable
data class Palette(
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val line: Color,
    val paper: Color,
    val paperSoft: Color,
    val accent: Color,
)

val LightPalette = Palette(
    ink = Ink,
    inkSoft = InkSoft,
    inkFaint = InkFaint,
    line = Line,
    paper = Paper,
    paperSoft = PaperSoft,
    accent = Accent,
)

val DarkPalette = Palette(
    ink = Color(0xFFEDEDED),
    inkSoft = Color(0xFFA8A8A8),
    inkFaint = Color(0xFF6E6E6E),
    line = Color(0xFF333333),
    paper = Color(0xFF121212),
    paperSoft = Color(0xFF1E1E1E),
    accent = Color(0xFF5B9BFF),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

