package com.spotifry.music.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Ember orange, the flame under the pan: the one accent. Buttons, the selected tab, progress, the saved mark and the glow at the top of each screen. */
val Ember = Color(0xFFFF8A3D)
val EmberDeep = Color(0xFFC2410C)

/** Muted tones for covers that have no image yet. Not part of the interface palette. */
val placeholderTones = listOf(Color(0xFF6D8FB0), Color(0xFF4F6D63), Color(0xFF8A4A5A), Color(0xFF5D5785), Color(0xFF7A5B37))

val darkColors = darkColorScheme(
    background = Color(0xFF0C0D10),
    surface = Color(0xFF17191E),
    surfaceContainer = Color(0xFF17191E),
    surfaceContainerHigh = Color(0xFF1F2228),
    outlineVariant = Color(0xFF1F2228),
    outline = Color(0xFF2A2E35),
    onBackground = Color(0xFFF2F3F5),
    onSurface = Color(0xFFF2F3F5),
    onSurfaceVariant = Color(0xFFA6ADB6),
    primary = Ember,
    onPrimary = Color(0xFF2A1205),
    secondaryContainer = Color(0xFF1F2228),
    onSecondaryContainer = Color(0xFFF2F3F5),
    error = Color(0xFFF2877A),
)

val lightColors = lightColorScheme(
    background = Color(0xFFF4F5F7),
    surface = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE6E8EC),
    outlineVariant = Color(0xFFE1E4E8),
    outline = Color(0xFFC9CDD3),
    onBackground = Color(0xFF15181C),
    onSurface = Color(0xFF15181C),
    onSurfaceVariant = Color(0xFF525A64),
    primary = EmberDeep,
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFE6E8EC),
    onSecondaryContainer = Color(0xFF15181C),
    error = Color(0xFFB3261E),
)

/** The "saved on this phone" mark takes the accent. */
@Composable
fun savedMarkColor(): Color = if (isDark()) Ember else EmberDeep
