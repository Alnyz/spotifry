package com.spotifry.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
fun SearchPage(services: Services, state: PlayerUiState, query: String, run: Int, modifier: Modifier = Modifier) {
    val player = services.player; val source = services.source
    var results by remember { mutableStateOf<List<Song>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(run) {
        if (query.isBlank()) return@LaunchedEffect
        busy = true; failed = false
        try { results = source.search(query.trim()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failed = true }
        busy = false
    }
    Column(modifier) {
        Kicker("Search")
        Text(if (query.isBlank()) "Type in the bar on top." else query.trim(), fontFamily = Display, fontSize = 40.sp, lineHeight = 42.sp, maxLines = 2, color = Palette.ink, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
        when {
            busy -> Text("Searching…", color = Palette.ink2)
            failed -> Text("Couldn't search. Check your connection, then try again.", color = Palette.ink2)
            results?.isEmpty() == true -> Text("No songs found.", color = Palette.ink2)
            results != null -> LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
                itemsIndexed(results!!, key = { _, s -> s.id }) { i, s ->
                    SongLine(services, i + 1, s, state.current?.id == s.id) { player.playList(results!!, i, "Search") }
                }
            }
        }
    }
}
