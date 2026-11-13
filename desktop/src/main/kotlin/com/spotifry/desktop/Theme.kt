package com.spotifry.desktop

import androidx.compose.ui.graphics.luminance
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
    CHILI("Chili red", Color(0xFFFF6A55), Color(0xFFC42B1C)),
    /** Picked by hand: the real shades come from [readableAccent], so these two are only a start. */
    CUSTOM("Custom", Color(0xFFFF8A3D), Color(0xFFC2410C));

    companion object {
        /** A backup from the phone may hold "PHONE" (the wallpaper's colour), which has no meaning here: it shows as Ember. */
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
    /** The dark and light shades of a hand-picked accent, set by the window while the choice is CUSTOM. */
    var custom by mutableStateOf<Pair<Color, Color>?>(null)
    /** The colour the window glows with: the playing cover's, or the accent when there is none. Set by the window. */
    var glow by mutableStateOf<Color?>(null)

    val bg get() = if (dark) Color(0xFF0C0D10) else Color(0xFFF4F5F7)
    val surface get() = if (dark) Color(0xFF17191E) else Color(0xFFFFFFFF)
    val high get() = if (dark) Color(0xFF1F2228) else Color(0xFFE6E8EC)
    val line get() = if (dark) Color(0xFF23262C) else Color(0xFFE1E4E8)
    val ink get() = if (dark) Color(0xFFF2F3F5) else Color(0xFF15181C)
    val ink2 get() = if (dark) Color(0xFFA6ADB6) else Color(0xFF525A64)
    val accent get() = custom.takeIf { accentChoice == AccentChoice.CUSTOM }?.let { if (dark) it.first else it.second } ?: if (dark) accentChoice.dark else accentChoice.light
    val onAccent get() = if (dark) Color(0xFF2A1205) else Color.White
    val danger get() = if (dark) Color(0xFFF2877A) else Color(0xFFB3261E)
}

/**
 * Albert Sans at weights 400 to 800, one file each, cut from the phone's variable font: the desktop's text drawing
 * ignores a variable font's weight setting. [shift] draws each weight that much heavier.
 */
private fun albertAt(shift: Int = 0) = FontFamily(listOf(400, 500, 600, 700, 800).map { w ->
    Font(resource = "font/albertsans-${minOf(w + shift, 800)}.ttf", weight = FontWeight(w))
})

val Albert = albertAt()

/**
 * Headlines, song names and lyrics: Albert Sans like the rest, two steps heavier (regular shows semi-bold), the phone's
 * look for titles. Ali's choice, 2026-11-12; was Noto Serif, then Unbounded for a day.
 */
val Display = albertAt(200)

/** Manrope, bold and extra bold: Home's big song name and section titles, after Ali's mockup (2026-11-12). */
val Manrope = FontFamily(Font(resource = "font/manrope-700.ttf", weight = FontWeight.Bold), Font(resource = "font/manrope-800.ttf", weight = FontWeight.ExtraBold))

/** Every heading: page and section titles, dialog titles, empty pages. Manrope extra bold whatever weight is asked. Ali's choice, 2026-11-12. */
val Heading = FontFamily(Font(resource = "font/manrope-800.ttf"))

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
    typography = Typography().with(Albert),
) { CompositionLocalProvider(LocalContentColor provides Palette.ink, content = content) }


private fun contrast(a: Color, b: Color): Float {
    val x = a.luminance(); val y = b.luminance()
    return (maxOf(x, y) + .05f) / (minOf(x, y) + .05f)
}

/** Hue 0..360, saturation and brightness 0..1. */
fun hsv(c: Color): Triple<Float, Float, Float> {
    val r = c.red; val g = c.green; val b = c.blue
    val max = maxOf(r, g, b); val d = max - minOf(r, g, b)
    val h = when {
        d == 0f -> 0f
        max == r -> 60 * (((g - b) / d) % 6)
        max == g -> 60 * ((b - r) / d + 2)
        else -> 60 * ((r - g) / d + 4)
    }
    return Triple((h + 360) % 360, if (max == 0f) 0f else d / max, max)
}

/**
 * Any picked colour made easy to read in both themes (at least 4.5 to 1): same hue, lighter on the dark theme if it would
 * sink into the background, deeper on the light theme if white text on it would fade. The phone's `readableAccent`, which is
 * written with Android's Compose Color; kept in step by hand. Returns the dark and the light shade.
 */
fun readableAccent(picked: Color): Pair<Color, Color> {
    val (h, s0, v0) = hsv(picked)
    var s = s0; var v = v0; var dark = picked
    repeat(60) {
        if (contrast(dark, Color(0xFF0C0D10)) >= 4.5f) return@repeat
        if (v < 1f) v = minOf(1f, v + .03f) else s = maxOf(0f, s - .03f)
        dark = Color.hsv(h, s, v)
    }
    s = s0; v = v0; var light = picked
    repeat(60) {
        if (contrast(light, Color.White) >= 4.5f) return@repeat
        v = maxOf(0f, v - .03f); s = minOf(1f, s + .02f)
        light = Color.hsv(h, s, v)
    }
    return dark to light
}
