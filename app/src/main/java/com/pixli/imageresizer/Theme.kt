package com.pixli.imageresizer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Blue = Color(0xFF0066FF)
val BlueSoft = Color(0xFFEAF2FF)
val Ink = Color(0xFF1B2433)
val Muted = Color(0xFF6B778C)
val Bg = Color(0xFFF4F7FC)
val Line = Color(0xFFE3E8F0)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Blue,
            onPrimary = Color.White,
            primaryContainer = BlueSoft,
            onPrimaryContainer = Blue,
            background = Bg,
            surface = Color.White,
            onSurface = Ink,
            onSurfaceVariant = Muted,
            surfaceVariant = Color(0xFFEEF2F8),
            outlineVariant = Line
        ),
        content = content
    )
}
