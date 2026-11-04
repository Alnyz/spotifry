package com.spotifry.music.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.spotifry.music.data.ThemeMode

private val LocalDark = staticCompositionLocalOf { false }

/** Dark or light as the app shows it: the phone's setting, unless Settings picks one. Use this, not isSystemInDarkTheme. */
@Composable
fun isDark(): Boolean = LocalDark.current

@Composable
fun MusicTheme(mode: ThemeMode = ThemeMode.PHONE, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.PHONE -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    // the status and gesture bar icons follow the app's theme, not the phone's, when Settings forces one
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = if (dark) darkColors else lightColors, typography = MusicTypography, content = content)
    }
}
