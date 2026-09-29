package com.a56camera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun A56CameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = AeroMidnight,
            surface = AeroCyan100.copy(alpha = 0.2f),
            primary = AquaBright,
            onPrimary = Color.White,
            secondary = IridescentPink,
            onSurface = Color.White
        ),
        typography = Typography(),
        content = content
    )
}
