package com.spotifry.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.spotifry.music.ui.Icons

/** Play (filled with the accent) and Shuffle, side by side, under a list's title. */
@Composable
fun PlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier, Arrangement.spacedBy(10.dp)) {
        Pill(Icons.Play, "Play", scheme.primary, scheme.onPrimary, onPlay)
        Pill(Icons.Shuffle, "Shuffle", scheme.surface, scheme.onSurface, onShuffle)
    }
}

@Composable
fun Pill(icon: ImageVector?, label: String, fill: Color, ink: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.height(48.dp).clip(CircleShape).background(fill).clickable(onClick = onClick).padding(start = 16.dp, end = 20.dp),
        Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides ink) {
            if (icon != null) Icon(icon, null, Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
