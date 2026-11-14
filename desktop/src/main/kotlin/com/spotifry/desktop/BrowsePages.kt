package com.spotifry.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.ShareText
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.PageContent
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.db.toEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A tile under the empty search: its playlists come from a fixed search for [query]. The same tiles as the phone's. */
data class Mood(val name: String, val line: String, val query: String, val color: Color, val art: DrawScope.() -> Unit)

/** Copied from the phone (ui/home/MoodsScreen.kt): keep the two lists alike. */
val MOODS = listOf(
    Mood("Chill", "Easy songs that keep things slow and quiet.", "chill hits", Color(0xFF15594C), { waves() }),
    Mood("Focus", "Mostly wordless music for reading or work.", "focus instrumental", Color(0xFF2F4A7A), { target() }),
    Mood("Workout", "Fast, hard songs to keep you moving.", "workout music playlist", Color(0xFFA32A1E), { dumbbell() }),
    Mood("Party", "Loud songs everyone can dance and sing to.", "party hits", Color(0xFFA8186A), { confetti() }),
    Mood("Sad", "Slow songs for when you need to feel it.", "sad songs playlist", Color(0xFF3F4C63), { rain() }),
    Mood("Sleep", "Soft, slow songs for falling asleep.", "soft songs for sleeping", Color(0xFF27235E), { moon() }),
    Mood("Feel good", "Bright songs to lift your day.", "feel good hits", Color(0xFF7A4200), { sun() }),
    Mood("Love songs", "Slow songs about falling for someone.", "love songs hits", Color(0xFF99204C), { heart() }),
    Mood("Indo pop", "New hits and old favourites from Indonesia.", "top hits indonesia", Color(0xFF74298A), { microphone() }),
    Mood("Dangdut", "Indonesia's own drum-led dance music, old and new.", "dangdut hits", Color(0xFF195B27), { drum() }),
    Mood("Indie", "Guitars and small bands away from the big charts.", "indie hits", Color(0xFF5A4C18), { cassette() }),
    Mood("Hip-hop", "Rap and beats, from the classics to now.", "hip hop hits", Color(0xFF4A3F9A), { speaker() }),
    Mood("R&B", "Smooth voices over soft, soulful grooves.", "r&b hits", Color(0xFF5C2A63), { vinyl() }),
    Mood("Rock", "Loud guitars and big choruses, old and new.", "rock hits", Color(0xFF2C2F36), { bolt() }),
    Mood("Jazz", "Swing, horns and piano to sit back with.", "jazz classics", Color(0xFF724510), { pianoKeys() }),
    Mood("Dance", "House, techno and other club music.", "electronic dance hits", Color(0xFF005A68), { levelBars() }),
)

/** Each tile's playlists, found once per run of the app. */
private val moodCache = HashMap<String, List<PageLink>>()
/** List checks running at once across every tile, so opening several quickly doesn't flood YouTube from one address. */
private val checks = Semaphore(4)

/**
 * The playlists a tile shows, as on the phone: a search finds lists that open with nothing to play or one song, so they
 * are checked a few at a time in the search's order until ten have five songs or more.
 */
internal suspend fun moodLists(source: MusicSource, mood: Mood, enough: Int = 10): List<PageLink> = moodCache[mood.name] ?: coroutineScope {
    val found = source.searchPages(mood.query, SearchType.PLAYLISTS)
        .distinctBy { it.url }.distinctBy { it.name.trim().lowercase() }.filterNot { "karaoke" in it.name.lowercase() }
    val passed = mutableListOf<PageLink>()
    var failure: Exception? = null
    for (group in found.chunked(4)) {
        if (passed.size >= enough) break
        val counts = group.map { link ->
            async { try { checks.withPermit { source.playableSongs(link) } } catch (e: CancellationException) { throw e } catch (e: Exception) { failure = e; 0 } }
        }.awaitAll()
        group.forEachIndexed { i, link -> if (counts[i] >= 5) passed += link }
    }
    // nothing because the checks failed is the connection, not the lists
    failure?.let { if (passed.isEmpty()) throw it }
    passed.take(enough).also { if (failure == null || it.size >= enough) moodCache[mood.name] = it }
}

/** One coloured tile with its white picture and name. */
@Composable
fun MoodTile(mood: Mood, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.height(104.dp).clip(RoundedCornerShape(16.dp)).background(mood.color).drawBehind { moodArt(mood.art) }.clickable(onClick = onOpen).padding(16.dp),
        contentAlignment = Alignment.BottomStart,
    ) { Text(mood.name, fontFamily = Heading, fontSize = 20.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** An album, artist or playlist in a grid: its cover (round for an artist), name and maker. */
@Composable
fun PageCard(link: PageLink, onOpen: () -> Unit) {
    val artist = link.type == SearchType.ARTISTS
    CoverCard(link.name, if (artist) "Artist" else link.by.ifBlank { link.type.label.removeSuffix("s") }, { w ->
        Cover(link.thumbnailUrl, w, if (artist) CircleShape else RoundedCornerShape(12.dp), large = true)
    }, width = 180.dp, onClick = onOpen)
}

/** A mood's page: its line, then the playlists found for it. */
@Composable
fun MoodPage(services: Services, mood: Mood, onBack: () -> Unit, open: (PageLink) -> Unit, modifier: Modifier = Modifier) {
    var tick by remember { mutableStateOf(0) }
    val lists by produceState<Result<List<PageLink>>?>(null, mood, tick) { value = null; value = runCatching { moodLists(services.source, mood) } }
    LazyVerticalGrid(GridCells.Adaptive(190.dp), modifier, horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(bottom = 24.dp + LocalBottomInset.current)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text("‹ Search", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(vertical = 6.dp, horizontal = 4.dp))
                Kicker("Moods and genres", Modifier.padding(top = 14.dp))
                Text(mood.name, fontFamily = Heading, fontSize = 44.sp, letterSpacing = (-1).sp, color = Palette.ink, modifier = Modifier.padding(top = 6.dp))
                Text(mood.line, color = Palette.ink2, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            }
        }
        val r = lists
        when {
            r == null -> item(span = { GridItemSpan(maxLineSpan) }) { Text("Finding playlists…", color = Palette.ink2) }
            r.isFailure -> item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Couldn't load ${mood.name}. Check your connection.", color = Palette.ink2)
                    Pill("Try again") { tick++ }
                }
            }
            r.getOrThrow().isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { Text("No playlists found for ${mood.name} right now. Try again later.", color = Palette.ink2) }
            else -> items(r.getOrThrow(), key = { it.url }) { PageCard(it) { open(it) } }
        }
    }
}

/**
 * An album, artist or playlist opened from Search or a mood: its cover and name, Play and Shuffle, Save as playlist
 * (not for an artist) and Share, then its songs. An artist shows its first ten songs until asked for all, and its albums.
 */
@Composable
fun PageView(services: Services, link: PageLink, backLabel: String, onBack: () -> Unit, open: (PageLink) -> Unit, go: (Route) -> Unit, modifier: Modifier = Modifier) {
    val lib = services.library
    val scope = rememberCoroutineScope()
    val state by services.player.state.collectAsState()
    var tick by remember { mutableStateOf(0) }
    val page by produceState<Result<PageContent>?>(null, link, tick) { value = null; value = runCatching { services.source.page(link) } }
    var all by remember(link) { mutableStateOf(false) }
    val artist = link.type == SearchType.ARTISTS
    val content = page?.getOrNull()
    val songs = content?.songs.orEmpty()
    val shown = if (artist && !all) songs.take(10) else songs
    val from = content?.name ?: link.name

    LazyVerticalGrid(GridCells.Adaptive(190.dp), modifier, horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(0.dp), contentPadding = PaddingValues(bottom = 24.dp + LocalBottomInset.current)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text("‹ $backLabel", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(vertical = 6.dp, horizontal = 4.dp))
                Row(Modifier.padding(top = 18.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Bottom) {
                    Cover(content?.thumbnailUrl ?: link.thumbnailUrl, 200.dp, if (artist) CircleShape else RoundedCornerShape(18.dp), large = true)
                    Column(Modifier.weight(1f)) {
                        Text(content?.kind ?: link.type.label.removeSuffix("s"), color = Palette.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(from, fontFamily = Heading, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-1).sp, color = Palette.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val by = content?.by ?: link.by
                        Text(listOfNotNull(by.ifBlank { null }, if (content != null) "${songs.size} ${if (songs.size == 1) "song" else "songs"}" else null).joinToString(" · "), color = Palette.ink2, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
                        if (songs.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill("Play all", Icons.Play, primary = true) { services.player.playList(songs, 0, from) }
                            Pill("Shuffle", Icons.Shuffle) { services.player.shuffleList(songs, from) }
                            if (!artist) Pill("Save as playlist", Icons.Plus) {
                                scope.launch {
                                    val name = ShareText.freeName(from, lib.db.playlists().observePlaylists().first().map { it.name })
                                    val id = lib.db.playlists().create(name)
                                    songs.forEach { s -> lib.db.songs().upsert(s.toEntity()); lib.db.playlists().addSong(id, s.id) }
                                    go(Route.Playlist(id))
                                }
                            }
                            Pill("Share") { services.sharing = ShareTarget.of(from, songs) }
                        }
                    }
                }
            }
        }
        val r = page
        when {
            r == null -> item(span = { GridItemSpan(maxLineSpan) }) { Text("Opening…", color = Palette.ink2, modifier = Modifier.padding(top = 12.dp)) }
            r.isFailure -> item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Couldn't open ${link.name}. Check your connection.", color = Palette.ink2)
                    Pill("Try again") { tick++ }
                }
            }
            else -> {
                if (artist && songs.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Songs") }
                if (songs.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("Nothing to play here.", color = Palette.ink2, modifier = Modifier.padding(top = 12.dp)) }
                shown.forEachIndexed { i, s ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "song-$i-${s.id}") { SongLine(services, i + 1, s, state.current?.id == s.id) { services.player.playList(songs, i, from) } }
                }
                if (artist && !all && songs.size > shown.size) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Show all ${songs.size}", color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable { all = true }.padding(horizontal = 10.dp, vertical = 8.dp))
                }
                val albums = content?.albums.orEmpty()
                if (albums.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Albums") }
                    items(albums, key = { "album-" + it.url }) { Box(Modifier.padding(bottom = 22.dp)) { PageCard(it) { open(it) } } }
                }
            }
        }
    }
}
