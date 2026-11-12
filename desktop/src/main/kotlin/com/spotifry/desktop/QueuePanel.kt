package com.spotifry.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.PlayerUiState

/** The queue opened from the bottom bar, in place of the side panel: what plays now, what comes next, and what was played. */
@Composable
fun QueuePanel(services: Services, state: PlayerUiState, width: Dp, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf(0) }
    Column(modifier.width(width).fillMaxHeight().background(if (Palette.dark) Color.Black.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.45f)).padding(top = 14.dp, start = 24.dp, end = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf("Queue", "Recently played").forEachIndexed { i, label ->
                HoverTab(label, tab == i, 14.sp, 0.sp) { tab = i }
            }
            Spacer(Modifier.weight(1f))
            Tip("Close") { Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) { Text("×", fontSize = 22.sp, color = Palette.ink2) } }
        }
        if (tab == 0) QueueTab(services, state) else RecentTab(services)
    }
}

@Composable
private fun QueueTab(services: Services, state: PlayerUiState) {
    val cur = state.current
    val upcoming = state.upNextIndices().filter { it != state.currentIndex }
    LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), contentPadding = PaddingValues(bottom = 130.dp)) {
        if (cur != null) {
            item { Heading("Now playing") }
            item { SongRow(cur, accent = true, onClick = {}) }
        }
        item { Heading(if (state.from != null) "Next from: ${state.from}" else "Next") }
        if (upcoming.isEmpty()) item { Text(nothingNextNote(state), color = Palette.ink2, fontSize = 14.sp) }
        itemsIndexed(upcoming, key = { _, i -> "$i-${state.queue.getOrNull(i)?.id}" }) { _, i ->
            val s = state.queue.getOrNull(i) ?: return@itemsIndexed
            SongRow(s, onClick = { services.player.skipTo(i) }, onRemove = { services.player.remove(i, s.id) }, services = services.takeIf { s.suggested })
        }
    }
}

@Composable
private fun RecentTab(services: Services) {
    val recent by services.library.db.history().observeRecent().collectAsState(emptyList())
    val songs = recent.map(SongEntity::toSong)
    LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), contentPadding = PaddingValues(bottom = 130.dp)) {
        item { Heading("Recently played") }
        if (songs.isEmpty()) item { Text("Nothing played yet.", color = Palette.ink2, fontSize = 14.sp) }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s -> SongRow(s, onClick = { services.player.playList(songs, i, "Recently played") }) }
    }
}

@Composable
private fun Heading(text: String) = Text(text, fontFamily = Display, fontSize = 20.sp, color = Palette.ink, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))

@Composable
private fun SongRow(s: Song, accent: Boolean = false, onClick: () -> Unit, onRemove: (() -> Unit)? = null, services: Services? = null) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(s.thumbnailUrl ?: s.localThumbPath, 42.dp, RoundedCornerShape(6.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, fontFamily = Display, fontSize = 16.sp, maxLines = 1, color = if (accent) Palette.accent else Palette.ink)
            Artist(s, 12.5.sp)
        }
        // a suggestion's menu has "Not this song"
        if (services != null) Box {
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.size(28.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) { Icon(Icons.More, "More", Modifier.size(18.dp), tint = Palette.ink2) }
            if (menu) SongMenu(services, s, { menu = false }) {}
        }
        if (onRemove != null) Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) { Text("×", color = Palette.ink2, fontSize = 18.sp) }
    }
}

/** Says what the player will really do once this song ends; the same rules as its similar songs. */
fun nothingNextNote(state: PlayerUiState): String = when {
    state.repeatMode == com.spotifry.music.player.RepeatMode.ONE -> "This song plays again when it ends."
    state.repeatMode == com.spotifry.music.player.RepeatMode.ALL && state.queue.size > 1 -> "The list starts again when this one ends."
    else -> "Similar songs are added as this one plays."
}
