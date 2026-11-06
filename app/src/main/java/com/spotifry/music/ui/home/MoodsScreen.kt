package com.spotifry.music.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
import com.spotifry.music.ui.components.ErrorRow
import com.spotifry.music.ui.components.LoadingRow
import com.spotifry.music.ui.components.LoadingShapes
import com.spotifry.music.ui.components.ScreenHeader
import com.spotifry.music.ui.components.SectionTitle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A tile under the empty search box. Its playlists come from a fixed search for [query]; words with "hits" find fewer stray personal lists. */
data class Mood(val name: String, val line: String, val query: String)

/** Names stay short: at 1.3x text on a 320 dp screen, a tile fits a word of about eight letters. */
val MOODS = listOf(
    Mood("Chill", "Easy songs that keep things slow and quiet.", "chill hits"),
    Mood("Focus", "Mostly wordless music for reading or work.", "focus instrumental"),
    Mood("Workout", "Fast, hard songs to keep you moving.", "workout music playlist"),
    Mood("Party", "Loud songs everyone can dance and sing to.", "party hits"),
    Mood("Sad", "Slow songs for when you need to feel it.", "sad songs playlist"),
    Mood("Sleep", "Soft, slow songs for falling asleep.", "soft songs for sleeping"),
    Mood("Feel good", "Bright songs to lift your day.", "feel good hits"),
    Mood("Love songs", "Slow songs about falling for someone.", "love songs hits"),
    Mood("Indo pop", "New hits and old favourites from Indonesia.", "top hits indonesia"),
    Mood("Dangdut", "Indonesia's own drum-led dance music, old and new.", "dangdut hits"),
    Mood("Indie", "Guitars and small bands away from the big charts.", "indie hits"),
    Mood("Hip-hop", "Rap and beats, from the classics to now.", "hip hop hits"),
    Mood("R&B", "Smooth voices over soft, soulful grooves.", "r&b hits"),
    Mood("Rock", "Loud guitars and big choruses, old and new.", "rock hits"),
    Mood("Jazz", "Swing, horns and piano to sit back with.", "jazz classics"),
    Mood("Dance", "House, techno and other club music.", "electronic dance hits"),
)

/** A list with fewer songs than this isn't worth a row. */
private const val MIN_SONGS = 5

/**
 * The playlists a tile shows. YouTube has no moods list NewPipe can read, so it's a search, and a search finds lists
 * that open with nothing to play (all hour-long mixes, say) or with one song. Each is checked first, so these don't show.
 */
internal suspend fun moodLists(source: MusicSource, mood: Mood): List<PageLink> = coroutineScope {
    val found = source.searchPages(mood.query, SearchType.PLAYLISTS)
        .distinctBy { it.url }.distinctBy { it.name.trim().lowercase() }.filterNot { "karaoke" in it.name.lowercase() }
    val songs = found.map { async { runCatching { source.playableSongs(it) } } }.awaitAll()
    // all of them failing is the connection, not the lists
    if (songs.isNotEmpty() && songs.all { it.isFailure }) songs.first().getOrThrow()
    found.filterIndexed { i, _ -> songs[i].getOrDefault(0) >= MIN_SONGS }
}

class MoodViewModel(private val source: MusicSource, val mood: Mood, private val cache: MutableMap<String, List<PageLink>>) : ViewModel() {
    /** Null while loading. */
    val lists = MutableStateFlow(cache[mood.name]?.let { Result.success(it) })
    init { if (lists.value == null) load() }
    fun load() {
        lists.value = null
        viewModelScope.launch {
            lists.value = runCatching { moodLists(source, mood) }
                // an empty answer isn't kept: it may just be a bad moment
                .onSuccess { if (it.isNotEmpty()) cache[mood.name] = it }
        }
    }
}

/** Two tiles a row, as rows of the page's own list, so Back finds the page where it was. */
internal fun LazyListScope.moodGrid(onOpen: (Mood) -> Unit) {
    item { SectionTitle("Moods and genres") }
    items(MOODS.chunked(2)) { row ->
        // as tall as the taller of the two when big text wraps a name
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 20.dp, end = 20.dp, bottom = 10.dp), Arrangement.spacedBy(10.dp)) {
            row.forEach { MoodTile(it, onOpen, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun MoodTile(mood: Mood, onOpen: (Mood) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxHeight().heightIn(min = 84.dp).clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainerHigh)
            .clickable(role = Role.Button) { onOpen(mood) }.padding(12.dp),
        Alignment.BottomStart,
    ) { Text(mood.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = scheme.onSurface) }
}

/** A tile's page: one plain line, then its playlists. */
@Composable
fun MoodScreen(vm: MoodViewModel, onBack: () -> Unit, actions: PageActions, bottomInset: Dp) {
    val state by vm.lists.collectAsStateWithLifecycle()
    val lists = state?.getOrNull()
    val mood = vm.mood
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 16.dp)) {
        item { ScreenHeader(mood.name, Modifier.padding(bottom = 12.dp), subtitle = mood.line, onBack = onBack) }
        when {
            state == null -> item { LoadingShapes(Modifier.padding(start = 20.dp, end = 8.dp)) { repeat(6) { LoadingRow() } } }
            lists == null -> item { ErrorRow(mood.name, vm::load, Modifier.padding(horizontal = 20.dp)) }
            lists.isEmpty() -> item {
                Text("No playlists found for ${mood.name} right now. Try again later.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> items(lists, key = { it.url }) { PageRow(it, actions, Modifier.padding(start = 20.dp, end = 8.dp)) }
        }
    }
}
