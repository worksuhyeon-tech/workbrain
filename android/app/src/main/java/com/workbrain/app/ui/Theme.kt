package com.workbrain.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 목업(docs/02-design/mockups)의 기본 색과 맞춘다
private val Light = lightColorScheme(
    primary = Color(0xFF0B6FD8),
    onPrimary = Color.White,
    error = Color(0xFFD93A2B),
    background = Color(0xFFF4F7FA),
    surface = Color.White,
)
private val Dark = darkColorScheme(
    primary = Color(0xFF5AB0FF),
    onPrimary = Color(0xFF06131D),
    error = Color(0xFFFF7A6B),
)

@Composable
fun WorkBrainTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
