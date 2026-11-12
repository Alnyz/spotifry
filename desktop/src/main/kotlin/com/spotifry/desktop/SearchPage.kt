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
import androidx.compose.runtime.collectAsState
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
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.produceState
import com.spotifry.music.player.MusicPlayer
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.data.source.MusicSource
import kotlinx.coroutines.launch

/** What Search looks for; Songs on every start, kept while the app is open, as on the phone. */
internal var searchType by mutableStateOf(SearchType.SONGS)

/**
 * Search: chips for what to look for, then the results. Songs come as a list, with music folder matches first; albums,
 * artists and playlists as a grid of covers that open their page. With nothing searched yet it shows the recent
 * searches and the mood tiles.
 */
@Composable
fun SearchPage(
    services: Services, state: PlayerUiState, query: String, run: Int, onSearch: (String) -> Unit, open: (PageLink) -> Unit, openMood: (Mood) -> Unit,
    modifier: Modifier = Modifier,
) {
    val player = services.player; val source = services.source
    val type = searchType
    val q = query.trim()
    val files by services.library.musicFolder.songs.collectAsState()
    val recent by services.library.recentSearches.list.collectAsState()
    // songs from the music folder that match come first, like the phone's songs on the phone
    val local = remember(run, files, type) { if (type == SearchType.SONGS) com.spotifry.music.data.localMatches(files, q) else emptyList() }
    // null while searching; a failure is the connection
    val results by produceState<Result<Pair<List<Song>, List<PageLink>>>?>(null, run, type) {
        if (q.isEmpty()) return@produceState
        value = null
        value = runCatching {
            when (type) {
                SearchType.SONGS -> source.search(q) to emptyList()
                SearchType.VIDEOS -> source.searchVideos(q) to emptyList()
                else -> emptyList<Song>() to source.searchPages(q, type)
            }
        }
    }
    val full = { span: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope -> androidx.compose.foundation.lazy.grid.GridItemSpan(span.maxLineSpan) }
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        androidx.compose.foundation.lazy.grid.GridCells.Adaptive(190.dp), modifier, horizontalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 24.dp + LocalBottomInset.current),
    ) {
        item(span = full) {
            Column {
                Kicker("Search")
                Text(if (q.isEmpty()) "Find any songs" else q, fontFamily = Heading, fontSize = 40.sp, lineHeight = 42.sp, maxLines = 2, color = Palette.ink, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                if (q.isNotEmpty()) Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchType.entries.forEach { t -> Chip(t.label, t == type) { searchType = t } }
                }
            }
        }
        if (q.isEmpty()) {
            if (recent.isNotEmpty()) {
                item(span = full) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SectionTitle("Recent searches", line = false) }
                        Text("Clear", color = Palette.ink2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { services.library.recentSearches.clear() }.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
                item(span = full) {
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        recent.forEach { r -> RecentChip(r, { onSearch(r) }) { services.library.recentSearches.remove(r) } }
                    }
                }
            }
            item(span = full) { SectionTitle("Moods and genres", line = false) }
            MOODS.forEach { m -> item(key = "mood-" + m.name) { MoodTile(m, { openMood(m) }, Modifier.padding(bottom = 20.dp)) } }
            return@LazyVerticalGrid
        }
        if (local.isNotEmpty()) {
            item(span = full) { SectionTitle("On this computer", line = false) }
            local.forEachIndexed { i, s -> item(span = full, key = "file " + s.id) { SongLine(services, i + 1, s, state.current?.id == s.id) { player.playList(local, i, "Search") } } }
            item(span = full) { SectionTitle("From YouTube", line = false) }
        }
        val r = results
        when {
            r == null -> item(span = full) { Text("Searching…", color = Palette.ink2) }
            r.isFailure -> item(span = full) { Text("Couldn't search. Check your connection, then try again.", color = Palette.ink2) }
            else -> {
                val (songs, pages) = r.getOrThrow()
                if (songs.isEmpty() && pages.isEmpty()) item(span = full) { Text("No ${type.word} found.", color = Palette.ink2) }
                songs.forEachIndexed { i, s -> item(span = full, key = "song-$i-" + s.id) { SongLine(services, i + 1, s, state.current?.id == s.id) { player.playList(songs, i, "Search “$q”") } } }
                items(pages.size, key = { "page-" + pages[it].url }) { Box(Modifier.padding(top = 8.dp, bottom = 14.dp)) { PageCard(pages[it]) { open(pages[it]) } } }
            }
        }
    }
}

/** A recent search: click to search it again, × to forget it. */
@Composable
private fun RecentChip(text: String, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Palette.ink.copy(alpha = 0.08f)).clickable(onClick = onClick).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text, fontSize = 14.sp, color = Palette.ink, maxLines = 1)
        Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) { Text("×", color = Palette.ink2, fontSize = 16.sp) }
    }
}
