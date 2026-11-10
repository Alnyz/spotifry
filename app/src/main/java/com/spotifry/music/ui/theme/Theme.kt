package com.spotifry.music.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.spotifry.music.data.ThemeMode

private val LocalDark = staticCompositionLocalOf { false }
/** The accent picked in Settings, both shades: for places that need the dark-theme one in light mode too. */
val LocalAccent = staticCompositionLocalOf { AccentChoice.EMBER.accent!! }

/** Dark or light as the app shows it: the phone's setting, unless Settings picks one. Use this, not isSystemInDarkTheme. */
@Composable
fun isDark(): Boolean = LocalDark.current

@Composable
fun MusicTheme(mode: ThemeMode = ThemeMode.PHONE, accent: Accent = AccentChoice.EMBER.accent!!, black: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.PHONE -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    // the status and gesture bar icons follow the app's theme, not the phone's, when Settings forces one
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    CompositionLocalProvider(LocalDark provides dark, LocalAccent provides accent) {
        MaterialTheme(colorScheme = if (dark) darkColors(accent, black) else lightColors(accent), typography = MusicTypography, content = content)
    }
}

/**
 * The dark theme on [background] whatever the app's theme, with the user's accent. While shown on the light theme, the
 * status bar icons turn light so they stay visible.
 */
@Composable
fun AlwaysDark(background: Color, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!isDark() && !view.isInEditMode) DisposableEffect(view) {
        val bars = (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.isAppearanceLightStatusBars = false
        onDispose { bars?.isAppearanceLightStatusBars = true }
    }
    CompositionLocalProvider(LocalDark provides true) {
        MaterialTheme(colorScheme = darkColors(LocalAccent.current).copy(background = background, surface = background), typography = MusicTypography, content = content)
    }
}
