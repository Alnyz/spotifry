package com.spotifry.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.MusicPlayer
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.data.source.MusicSource
import kotlinx.coroutines.launch

@Composable
fun SearchPage(player: MusicPlayer, source: MusicSource, state: PlayerUiState, initialQuery: String = "", modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<Song>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run() {
        if (query.isBlank()) return
        busy = true; failed = false
        scope.launch {
            try { results = source.search(query.trim()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failed = true }
            busy = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (initialQuery.isNotBlank()) run() }
    Column(modifier) {
        Text("Search", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Row(
            Modifier.padding(top = 18.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(Palette.surface).border(1.dp, Palette.line, RoundedCornerShape(26.dp)).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Search, null, Modifier.size(20.dp), tint = Palette.ink2)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search for a song or an artist", color = Palette.ink2, fontSize = 16.sp)
                BasicTextField(
                    query, { query = it }, singleLine = true, textStyle = TextStyle(color = Palette.ink, fontSize = 16.sp), cursorBrush = SolidColor(Palette.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run() }),
                    modifier = Modifier.fillMaxWidth().onEnter(::run),
                )
            }
        }
        when {
            busy -> Text("Searching…", color = Palette.ink2, modifier = Modifier.padding(top = 24.dp))
            failed -> Text("Couldn't search. Check your connection, then try again.", color = Palette.ink2, modifier = Modifier.padding(top = 24.dp))
            results?.isEmpty() == true -> Text("No songs found.", color = Palette.ink2, modifier = Modifier.padding(top = 24.dp))
            results != null -> LazyColumn(Modifier.padding(top = 18.dp)) {
                itemsIndexed(results!!, key = { _, s -> s.id }) { i, s ->
                    SongRow(i + 1, s, playing = state.current?.id == s.id) { player.playList(results!!, i, "Search") }
                }
            }
        }
    }
}

@Composable
fun SongRow(number: Int, s: Song, playing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(if (playing) Palette.surface else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(number.toString(), color = Palette.ink2, fontSize = 13.sp, modifier = Modifier.width(24.dp))
        Cover(s.thumbnailUrl, 40.dp, RoundedCornerShape(8.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, color = if (playing) Palette.accent else Palette.ink)
            Text(s.artist, color = Palette.ink2, fontSize = 13.sp, maxLines = 1)
        }
        Text(clock(s.durationSec * 1000), color = Palette.ink2, fontSize = 13.sp)
    }
}
