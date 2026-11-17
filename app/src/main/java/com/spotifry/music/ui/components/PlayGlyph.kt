package com.spotifry.music.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.ui.Icons

/** Pause while playing, a ring while the song is on its way, Play otherwise. */
@Composable
fun PlayGlyph(state: PlayerUiState, size: Dp, tint: Color = Color.Unspecified) {
    if (state.loading) CircularProgressIndicator(
        Modifier.size(size).semantics { contentDescription = "Loading" },
        color = if (tint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else tint, strokeWidth = size / 10,
    ) else Icon(if (state.isPlaying) Icons.Pause else Icons.Play, if (state.isPlaying) "Pause" else "Play", Modifier.size(size), tint = tint)
}

/** True once the song has been loading for [afterMs] without sound, so "slow" is only said when it is. */
@Composable
fun rememberSlow(loading: Boolean, afterMs: Long = 8_000): Boolean {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(loading) { slow = false; if (loading) { kotlinx.coroutines.delay(afterMs); slow = true } }
    return slow && loading
}

const val SLOW_TEXT = "Connection is slow"
