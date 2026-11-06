package com.spotifry.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.theme.glass
import dev.chrisbanes.haze.HazeState

/** Glass element 1 of 2. Sits above the bottom bar on every tab. */
@Composable
fun MiniPlayer(state: PlayerUiState, position: () -> Long, haze: HazeState, onToggle: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val song = state.current ?: return
    val shape = RoundedCornerShape(28.dp)
    Box(modifier.fillMaxWidth().height(com.spotifry.music.ui.FadeBarSpec.MINI).glass(haze, shape).clickable(onClickLabel = "Open player", onClick = onOpen)) {
        Row(Modifier.padding(start = 7.dp, end = 4.dp).height(com.spotifry.music.ui.FadeBarSpec.MINI), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            Cover(song.id, song.thumbnailUrl, song.localThumbPath, 42.dp, radius = 21.dp)
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onToggle, Modifier.size(48.dp)) { Icon(if (state.isPlaying) Icons.Pause else Icons.Play, if (state.isPlaying) "Pause" else "Play") }
            IconButton(onNext, Modifier.size(48.dp)) { Icon(Icons.Next, "Next song") }
        }
        val color = MaterialTheme.colorScheme.primary
        // position is read while drawing, so each tick only repaints this line
        Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 26.dp).fillMaxWidth().height(2.dp).drawBehind {
            val fraction = if (state.durationMs > 0) (position().toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            drawRect(color, size = size.copy(width = size.width * fraction))
        })
    }
}
