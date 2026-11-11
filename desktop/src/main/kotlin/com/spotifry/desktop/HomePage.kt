package com.spotifry.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.spreadShuffle
import kotlinx.coroutines.launch
import java.time.LocalTime

@Composable
fun HomePage(services: Services, go: (Route) -> Unit, modifier: Modifier = Modifier) {
    val lib = services.library
    val recent by lib.db.history().observeRecent().collectAsState(emptyList())
    val lists by lib.db.playlists().observePlaylists().collectAsState(emptyList())
    val covers by lib.db.playlists().observeCoverRows().collectAsState(emptyList())
    val state by services.player.state.collectAsState()
    val scope = rememberCoroutineScope()
    val recentSongs = recent.map(SongEntity::toSong)
    val hero: Song? = state.current ?: recentSongs.firstOrNull()
    val others = recentSongs.filter { it.id != hero?.id }
    val hour = LocalTime.now().hour

    LazyColumn(modifier) {
        item { Text(buildGreeting(hour), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        if (hero == null && lists.isEmpty()) item {
            EmptyState("Nothing played yet", "What you play shows up here.")
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Search for a song", Icons.Search, primary = true) { go(Route.Search) }
                    Pill("Import a playlist", Icons.Download) { services.importer.open() }
                }
            }
        }
        if (hero != null) item {
            val playingThis = state.current?.id == hero.id
            val tag = when { playingThis && state.wantsPlay -> "Playing"; playingThis -> "Paused"; else -> "Last played" }
            Row(
                Modifier.padding(top = 22.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.surface).border(1.dp, Palette.line, RoundedCornerShape(24.dp)).padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(hero.thumbnailUrl ?: hero.localThumbPath, 180.dp, RoundedCornerShape(18.dp), large = true)
                Column(Modifier.weight(1f)) {
                    Text(tag, color = Palette.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(hero.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                    Text(hero.artist, color = Palette.ink2, fontSize = 16.sp, maxLines = 1)
                    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill(if (playingThis && state.wantsPlay) "Pause" else if (playingThis) "Play" else "Play again", if (playingThis && state.wantsPlay) Icons.Pause else Icons.Play, primary = true) {
                            if (playingThis) services.player.toggle() else services.player.playList(listOf(hero), 0, "Home")
                        }
                        if (recentSongs.size > 1) Pill("Shuffle", Icons.Shuffle) { services.player.shuffleList(recentSongs, "Recently played") }
                        Pill("Save", Icons.Download) { scope.launch { lib.downloads.save(hero) } }
                    }
                }
            }
        }
        if (others.isNotEmpty()) {
            item { SectionTitle("Recently played") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    itemsIndexed(others.take(12)) { i, s ->
                        CoverCard(s.title, s.artist, { w -> Cover(s.thumbnailUrl ?: s.localThumbPath, w, RoundedCornerShape(14.dp)) }, onClick = { services.player.playList(others, i, "Recently played") })
                    }
                }
            }
        }
        if (lists.isNotEmpty()) {
            item { SectionTitle("Your playlists") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    itemsIndexed(lists) { _, p ->
                        val art = covers.filter { it.playlistId == p.id }.take(4).map { it.song.thumbnailUrl }
                        CoverCard(p.name, "${p.songCount} songs · ${p.savedCount} saved", { w -> Mosaic(art, w) }, onClick = { go(Route.Playlist(p.id)) })
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

fun buildGreeting(hour: Int) = when { hour < 12 -> "Good morning."; hour < 18 -> "Good afternoon."; else -> "Good evening." }
