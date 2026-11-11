package com.spotifry.music.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

const val FADE_MS = 250

/** The system's "remove animations" setting is on: things swap, nothing fades or pulses. */
@Composable
fun rememberStill(): Boolean {
    val context = LocalContext.current
    return remember { animationsOff(context) }
}

fun animationsOff(context: android.content.Context) = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * Fades in once when it first appears, over [FADE_MS]; nothing runs after that. Off ([on] false, or animations removed in
 * the system settings) it is just there. Only the alpha changes, so nothing moves.
 */
fun Modifier.fadeIn(on: Boolean = true): Modifier = composed {
    val start = fadeStart(on, rememberStill())
    if (start == 1f) this
    else {
        val alpha = remember { Animatable(start) }
        LaunchedEffect(Unit) { alpha.animateTo(1f, tween(FADE_MS)) }
        graphicsLayer { this.alpha = alpha.value }
    }
}

/** Where a fade starts: 1 (already there, no animation) when it is off or the system removed animations, else 0. */
internal fun fadeStart(on: Boolean, still: Boolean) = if (!on || still) 1f else 0f
