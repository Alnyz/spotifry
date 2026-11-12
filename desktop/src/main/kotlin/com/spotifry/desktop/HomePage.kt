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
        if (hero == null && lists.isEmpty()) item {
            Kicker("Welcome")
            Text("Nothing played yet.", fontFamily = Display, fontSize = 56.sp, lineHeight = 58.sp, letterSpacing = (-1.5).sp, color = Palette.ink, modifier = Modifier.padding(top = 8.dp))
            Text("What you play shows up here.", color = Palette.ink2, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Import a playlist", Icons.Download, primary = true) { services.importer.open() }
            }
        }
        if (hero != null) item {
            val playingThis = state.current?.id == hero.id
            val tag = when { playingThis && state.wantsPlay -> "Playing"; playingThis -> "Paused"; else -> "Last played" }
            val song: @Composable () -> Unit = {
                Column {
                    Kicker("$tag · ${buildGreeting(hour).trimEnd('.').lowercase()}")
                    Text(hero.title, fontFamily = Display, fontSize = 56.sp, lineHeight = 58.sp, letterSpacing = (-1.5).sp, maxLines = 2, color = Palette.ink, modifier = Modifier.padding(top = 8.dp))
                    Text(hero.artist, color = Palette.accent, fontSize = 16.sp, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill(if (playingThis && state.wantsPlay) "Pause" else if (playingThis) "Play" else "Play again", if (playingThis && state.wantsPlay) Icons.Pause else Icons.Play, primary = true) {
                            if (playingThis) services.player.toggle() else services.player.playList(listOf(hero), 0, "Home")
                        }
                        if (recentSongs.size > 1) Pill("Shuffle", Icons.Shuffle) { services.player.shuffleList(recentSongs, "Recently played") }
                        Pill("Save", Icons.Download) { scope.launch { lib.downloads.save(hero) } }
                    }
                }
            }
            // the song and your rotation side by side; on a narrow window the rotation goes under the song
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth > 900.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { song() }
                    RotationCard(services, Modifier.width(500.dp)) { go(Route.Rotation) }
                } else Column {
                    song()
                    RotationCard(services, Modifier.padding(top = 24.dp).fillMaxWidth()) { go(Route.Rotation) }
                }
            }
        }
        if (others.isNotEmpty()) {
            item { SectionTitle("Recently played") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    itemsIndexed(others.take(12)) { i, s ->
                        CoverCard(s.title, s.artist, { w -> Cover(s.thumbnailUrl ?: s.localThumbPath, w, RoundedCornerShape(8.dp)) }, onClick = { services.player.playList(others, i, "Recently played") })
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
        item { Spacer(Modifier.height(24.dp + LocalBottomInset.current)) }
    }
}

fun buildGreeting(hour: Int) = when { hour < 12 -> "Good morning."; hour < 18 -> "Good afternoon."; else -> "Good evening." }
