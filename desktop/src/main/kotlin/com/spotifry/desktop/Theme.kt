package com.spotifry.desktop

import androidx.compose.foundation.border
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** The phone app's colours: ember orange on near-black. */
object Palette {
    val bg = Color(0xFF0C0D10)
    val surface = Color(0xFF17191E)
    val high = Color(0xFF1F2228)
    val line = Color(0xFF23262C)
    val ink = Color(0xFFF2F3F5)
    val ink2 = Color(0xFFA6ADB6)
    val accent = Color(0xFFFF8A3D)
    val onAccent = Color(0xFF2A1205)
}

private val albert = FontFamily(listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { Font(resource = "font/albertsans.ttf", weight = it) })

private fun Typography.with(f: FontFamily) = copy(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f), displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f), headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f), titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f), bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f), labelSmall = labelSmall.copy(fontFamily = f),
)

@Composable
fun SpotifryTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        background = Palette.bg, surface = Palette.surface, surfaceContainer = Palette.surface, surfaceContainerHigh = Palette.high,
        outline = Palette.line, outlineVariant = Palette.line, onBackground = Palette.ink, onSurface = Palette.ink,
        onSurfaceVariant = Palette.ink2, primary = Palette.accent, onPrimary = Palette.onAccent,
    ),
    typography = Typography().with(albert),
) { CompositionLocalProvider(LocalContentColor provides Palette.ink, content = content) }

/** The glass look, used on three things only: the sidebar, the player bar and (later) dialogs. */
@Composable
fun Modifier.glass(state: HazeState, shape: Shape, tint: Float = 0.80f): Modifier =
    clip(shape)
        .hazeEffect(state) { style = HazeStyle(backgroundColor = Palette.bg, tint = HazeTint(Palette.bg.copy(alpha = tint)), blurRadius = 26.dp) }
        .border(1.dp, Color.White.copy(alpha = 0.07f), shape)
