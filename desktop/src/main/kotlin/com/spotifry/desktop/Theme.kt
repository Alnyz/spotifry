package com.spotifry.desktop

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/** Accent colours with a bright shade for the dark theme and a deeper one for the light theme, as on the phone. */
enum class AccentChoice(val label: String, val dark: Color, val light: Color) {
    EMBER("Ember orange", Color(0xFFFF8A3D), Color(0xFFC2410C)),
    BLUE("Blue", Color(0xFF5B95FF), Color(0xFF1F5FD6)),
    GOLDEN("Golden", Color(0xFFF5B03D), Color(0xFF9A5800)),
    CHILI("Chili red", Color(0xFFFF6A55), Color(0xFFC42B1C));

    companion object {
        /** A backup from the phone may hold "PHONE" or "CUSTOM", which have no meaning here: those show as Ember. */
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: EMBER
    }
}

/**
 * The colours in use. Reading them inside a composable follows [dark] and [accent], so changing either redraws the window.
 * Dark is the phone's dark theme; light is its light theme.
 */
object Palette {
    var dark by mutableStateOf(true)
    var accentChoice by mutableStateOf(AccentChoice.EMBER)
    /** The colour the window glows with: the playing cover's, or the accent when there is none. Set by the window. */
    var glow by mutableStateOf<Color?>(null)

    val bg get() = if (dark) Color(0xFF0C0D10) else Color(0xFFF4F5F7)
    val surface get() = if (dark) Color(0xFF17191E) else Color(0xFFFFFFFF)
    val high get() = if (dark) Color(0xFF1F2228) else Color(0xFFE6E8EC)
    val line get() = if (dark) Color(0xFF23262C) else Color(0xFFE1E4E8)
    val ink get() = if (dark) Color(0xFFF2F3F5) else Color(0xFF15181C)
    val ink2 get() = if (dark) Color(0xFFA6ADB6) else Color(0xFF525A64)
    val accent get() = if (dark) accentChoice.dark else accentChoice.light
    val onAccent get() = if (dark) Color(0xFF2A1205) else Color.White
    val danger get() = if (dark) Color(0xFFF2877A) else Color(0xFFB3261E)
}

/** The serif used for headlines, song titles and lyrics. */
val Serif = FontFamily(
    Font(resource = "font/serif-regular.ttf", weight = FontWeight.Normal), Font(resource = "font/serif-medium.ttf", weight = FontWeight.Medium),
    Font(resource = "font/serif-regular.ttf", weight = FontWeight.Bold), Font(resource = "font/serif-italic.ttf", weight = FontWeight.Normal, style = androidx.compose.ui.text.font.FontStyle.Italic),
)

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
    colorScheme = (if (Palette.dark) darkColorScheme() else lightColorScheme()).copy(
        background = Palette.bg, surface = Palette.surface, surfaceContainer = Palette.surface, surfaceContainerHigh = Palette.high,
        outline = Palette.line, outlineVariant = Palette.line, onBackground = Palette.ink, onSurface = Palette.ink,
        onSurfaceVariant = Palette.ink2, primary = Palette.accent, onPrimary = Palette.onAccent,
    ),
    typography = Typography().with(albert),
) { CompositionLocalProvider(LocalContentColor provides Palette.ink, content = content) }
