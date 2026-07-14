package com.vibedrop.mobile.nativeapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VibeDropLightColors = lightColorScheme(
    primary = Color(0xFF168DF7),
    secondary = Color(0xFF4E3F72),
    background = Color(0xFFF4F8FC),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF111827),
    onSurface = Color(0xFF111827)
)

@Composable
fun VibeDropTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VibeDropLightColors,
        typography = Typography(),
        content = content
    )
}
