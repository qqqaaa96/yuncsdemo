package com.csdemo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink),
    bodyMedium = TextStyle(fontSize = 14.sp, color = Ink),
    bodySmall = TextStyle(fontSize = 12.sp, color = InkSoft),
    labelSmall = TextStyle(fontSize = 11.sp, color = InkFaint)
)

