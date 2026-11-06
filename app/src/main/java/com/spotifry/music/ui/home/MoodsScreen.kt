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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A tile under the empty search box. Its playlists come from a fixed search for [query]; words with "hits" find fewer stray personal lists. */
data class Mood(val name: String, val line: String, val query: String, val color: Color, val art: DrawScope.() -> Unit)

/** Short names keep their full size at normal text. At very large text on a narrow screen [MoodTile] shrinks them to fit. */
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

/** A list with fewer songs than this isn't worth a row. */
private const val MIN_SONGS = 5

/** How many lists a tile looks for; on metered data with data saver on, fewer. */
private const val ENOUGH_LISTS = 10
private const val ENOUGH_LISTS_LOW = 5

/**
 * List checks running at once, across every tile. Opening several tiles quickly must not flood YouTube from one
 * address, or the next song's link could be refused. A page left behind holds its permit until its request returns.
 */
private const val CHECKS_AT_ONCE = 4
private val checks = Semaphore(CHECKS_AT_ONCE)

/** [keep]: nothing failed or enough lists passed, so a later visit wouldn't find more. */
internal class MoodFind(val lists: List<PageLink>, val keep: Boolean)

/**
 * The playlists a tile shows. YouTube has no moods list NewPipe can read, so it's a search, and a search finds lists
 * that open with nothing to play (all hour-long mixes, say) or with one song. They're checked a few at a time in the
 * search's order until there are [enough].
 */
internal suspend fun moodLists(source: MusicSource, mood: Mood, enough: Int = ENOUGH_LISTS): MoodFind {
    val found = source.searchPages(mood.query, SearchType.PLAYLISTS)
        .distinctBy { it.url }.distinctBy { it.name.trim().lowercase() }.filterNot { "karaoke" in it.name.lowercase() }
    val passed = mutableListOf<PageLink>()
    var failure: Exception? = null
    for (group in found.chunked(CHECKS_AT_ONCE)) {
        if (passed.size >= enough) break
        val songs = coroutineScope {
            group.map { link ->
                async {
                    try { checks.withPermit { source.playableSongs(link) } } catch (e: CancellationException) { throw e } catch (e: Exception) { failure = e; 0 }
                }
            }.awaitAll()
        }
        group.forEachIndexed { i, link -> if (songs[i] >= MIN_SONGS) passed += link }
    }
    // nothing to show because checks failed is the connection, not the lists
    failure?.let { if (passed.isEmpty()) throw it }
    return MoodFind(passed.take(enough), failure == null || passed.size >= enough)
}

class MoodViewModel(
    private val source: MusicSource, val mood: Mood, private val cache: MutableMap<String, List<PageLink>>, private val low: () -> Boolean = { false },
) : ViewModel() {
    /** Null while loading. */
    val lists = MutableStateFlow(cache[mood.name]?.let { Result.success(it) })
    init { if (lists.value == null) load() }
    fun load() {
        lists.value = null
        viewModelScope.launch {
            lists.value = try {
                val found = moodLists(source, mood, if (low()) ENOUGH_LISTS_LOW else ENOUGH_LISTS)
                // an empty or part-failed answer isn't kept: it may just be a bad moment
                if (found.keep && found.lists.isNotEmpty()) cache[mood.name] = found.lists
                Result.success(found.lists)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}

/** Two tiles a row, as rows of the page's own list, so Back finds the page where it was. */
internal fun LazyListScope.moodGrid(onOpen: (Mood) -> Unit) {
    item { SectionTitle("Moods and genres") }
    moodTiles(MOODS, onOpen)
}

internal fun LazyListScope.moodTiles(moods: List<Mood>, onOpen: (Mood) -> Unit) {
    items(moods.chunked(2)) { row ->
        // as tall as the taller of the two when big text wraps a name
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 20.dp, end = 20.dp, bottom = 10.dp), Arrangement.spacedBy(10.dp)) {
            row.forEach { MoodTile(it, onOpen, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun MoodTile(mood: Mood, onOpen: (Mood) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxHeight().heightIn(min = 96.dp).clip(RoundedCornerShape(16.dp)).background(mood.color).drawBehind { moodArt(mood.art) }
            .clickable(role = Role.Button) { onOpen(mood) }.padding(12.dp),
        Alignment.BottomStart,
    ) {
        // one line, a little smaller if it must be, so a long word never breaks in the middle
        val style = MaterialTheme.typography.titleLarge
        BasicText(
            mood.name, style = style.copy(fontWeight = FontWeight.Bold, color = Color.White), maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis, autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize),
        )
    }
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
