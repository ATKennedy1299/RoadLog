package com.roadlog.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RoadLogColorScheme = darkColorScheme(
    background = BackgroundBlack,
    surface = SurfaceDark,
    surfaceVariant = SurfaceRaised,
    primary = AccentGreen,
    secondary = AccentAmber,
    error = AccentRed,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onPrimary = BackgroundBlack,
    outline = DividerColor
)

@Composable
fun RoadLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RoadLogColorScheme,
        typography = RoadLogTypography,
        content = content
    )
}
