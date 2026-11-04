package com.spotifry.music.ui.theme

import android.content.Context
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** The accent in both themes: [dark] is the brighter shade for the dark theme, [light] the deeper one for the light theme. */
data class Accent(val dark: Color, val light: Color) {
    fun forTheme(darkTheme: Boolean) = if (darkTheme) dark else light
}

/** Ready colours, each with shades checked by hand; plus the wallpaper's colour and the user's own. */
enum class AccentChoice(val label: String, val accent: Accent?) {
    EMBER("Ember orange", Accent(Ember, EmberDeep)),
    BLUE("Blue", Accent(Color(0xFF5B95FF), Color(0xFF1F5FD6))),
    GOLDEN("Golden", Accent(Color(0xFFF5B03D), Color(0xFF9A5800))),
    CHILI("Chili red", Accent(Color(0xFFFF6A55), Color(0xFFC42B1C))),
    PHONE("Phone", null),
    CUSTOM("Custom", null);

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: EMBER
    }
}

/** The accent the app shows for [choice]; [custom] is the colour picked by hand (ARGB). */
fun accentFor(choice: AccentChoice, custom: Int, context: Context): Accent = when (choice) {
    AccentChoice.PHONE -> Accent(dynamicDarkColorScheme(context).primary, dynamicLightColorScheme(context).primary)
    AccentChoice.CUSTOM -> readableAccent(Color(custom))
    else -> choice.accent!!
}

private val DarkBackground = Color(0xFF0C0D10)
private val DarkInk = Color(0xFF15181C)

fun contrast(a: Color, b: Color): Float {
    val x = a.luminance(); val y = b.luminance()
    return (maxOf(x, y) + .05f) / (minOf(x, y) + .05f)
}

/** White or near-black, whichever reads better on [c]. */
fun onAccent(c: Color): Color = if (contrast(c, Color.White) >= contrast(c, DarkInk)) Color.White else DarkInk

/**
 * Any picked colour made easy to read in both themes (at least 4.5 to 1, the usual bar for text): same hue,
 * lighter on the dark theme if it would sink into the background, deeper on the light theme if white text on it would fade.
 */
fun readableAccent(picked: Color): Accent {
    val (h, s0, v0) = hsv(picked)
    var s = s0; var v = v0; var dark = picked
    repeat(60) {
        if (contrast(dark, DarkBackground) >= 4.5f) return@repeat
        // brighter first; once at full brightness, take out colour (toward white)
        if (v < 1f) v = minOf(1f, v + .03f) else s = maxOf(0f, s - .03f)
        dark = Color.hsv(h, s, v)
    }
    s = s0; v = v0; var light = picked
    repeat(60) {
        if (contrast(light, Color.White) >= 4.5f) return@repeat
        // deeper and a touch richer, so orange turns burnt orange instead of brown
        v = maxOf(0f, v - .03f); s = minOf(1f, s + .02f)
        light = Color.hsv(h, s, v)
    }
    return Accent(dark, light)
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
