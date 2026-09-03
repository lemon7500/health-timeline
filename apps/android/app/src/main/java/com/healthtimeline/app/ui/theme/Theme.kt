package com.healthtimeline.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors = lightColorScheme(
    primary = Color(0xFF2F6B56),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEDE6),
    onPrimaryContainer = Color(0xFF123C2E),
    secondary = Color(0xFFD26A5C),
    background = Color(0xFFF7FAF8),
    surface = Color.White,
    error = Color(0xFFBA1A1A)
)

@Composable
fun HealthTimelineTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
