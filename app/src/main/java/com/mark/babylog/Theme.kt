package com.mark.babylog

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light =
    lightColorScheme(
        primary = Color(0xFF65558F),
        secondary = Color(0xFF7D5260),
        surfaceVariant = Color(0xFFE7E0EC),
    )

internal val Dark =
    darkColorScheme(
        primary = Color(0xFFD0BCFF),
        secondary = Color(0xFFEFB8C8),
        background = Color(0xFF141218),
        surface = Color(0xFF211F26),
    )

@Composable
fun BabyTheme(content: @Composable () -> Unit) =
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Typography(),
        content = content,
    )
