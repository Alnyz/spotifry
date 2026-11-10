package com.spotifry.music.ui.spotifried

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.db.ListenRow
import com.spotifry.music.data.db.SongYearEntity
import com.spotifry.music.data.spotifried.CardId
import com.spotifry.music.data.spotifried.SpotifriedStats
import com.spotifry.music.data.spotifried.WrappedPeriod
import com.spotifry.music.data.spotifried.calendarYear
import com.spotifry.music.data.spotifried.morePlaysNeeded
import com.spotifry.music.data.spotifried.periodChoices
import com.spotifry.music.data.spotifried.shownCards
import com.spotifry.music.data.spotifried.spotifriedStats
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.theme.AlwaysDark
import com.spotifry.music.ui.theme.StoryBlack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SpotifriedState(
    val stats: SpotifriedStats, val cards: List<CardId>, val choices: List<WrappedPeriod>,
    /** Songs of the period with / without a year lookup done yet. */
    val checked: Int, val unchecked: Int,
)

class SpotifriedViewModel(listens: Flow<List<ListenRow>>, years: Flow<List<SongYearEntity>>, private val today: () -> LocalDate = LocalDate::now) : ViewModel() {
    private val picked = MutableStateFlow<WrappedPeriod?>(null)
    private val zone = ZoneId.systemDefault()

    private fun build(rows: List<ListenRow>, rowsWithYear: List<SongYearEntity>, pick: WrappedPeriod?): SpotifriedState {
        val now = today()
        val first = rows.minOfOrNull { it.startedAt }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: now
        val period = pick ?: calendarYear(now.year, now)
        val stats = spotifriedStats(rows, rowsWithYear.mapNotNull { r -> r.year?.let { r.songId to it } }.toMap(), period, zone, rowsWithYear.map { it.songId }.toSet())
        return SpotifriedState(stats, shownCards(stats), periodChoices(first, now), stats.age.songsTotal - stats.age.unchecked, stats.age.unchecked)
    }

    /** Null until the log and the years have both been read. */
    val state: StateFlow<SpotifriedState?> = combine(listens, years, picked, ::build).flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun pick(p: WrappedPeriod) { picked.value = p }
}

@Composable
fun SpotifriedScreen(vm: SpotifriedViewModel, onBack: () -> Unit, bottomInset: Dp) {
    val loaded by vm.state.collectAsStateWithLifecycle()
    val state = loaded ?: return
    // a new period starts again from the first card
    var index by rememberSaveable(state.stats.period) { mutableIntStateOf(0) }
    StoryTheme { Column(Modifier.fillMaxSize().padding(bottom = bottomInset)) {
        IconButton(onBack, Modifier.padding(start = 8.dp, top = 4.dp)) { Icon(Icons.Back, "Back") }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.choices, key = { it.label }) { p ->
                FilterChip(p == state.stats.period, { vm.pick(p) }, { Text(p.label) })
            }
        }
        if (state.cards.isEmpty()) NotEnough(morePlaysNeeded(state.stats))
        else StoryShell(state.cards, index, { index = it }) { StoryCard(it, state) {} }
    } }
}

/** The story is always the dark screen, whatever the app's theme; the accent stays the user's. */
@Composable
internal fun StoryTheme(content: @Composable () -> Unit) = AlwaysDark(StoryBlack) {
    // a Surface, so plain text takes the light ink rather than the app's dark one
    Surface(Modifier.fillMaxSize(), color = StoryBlack) { content() }
}

@Composable
internal fun NotEnough(more: Int) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), Arrangement.Center) {
        Text("Not enough listening yet", style = MaterialTheme.typography.headlineMedium)
        Text("$more more plays to go", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Tasks 11-12 draw the cards still shown by name. */
@Composable
internal fun StoryCard(id: CardId, state: SpotifriedState, onShare: () -> Unit) {
    when (id) {
        CardId.INTRO -> IntroCard(state.stats)
        CardId.TOP_SONG -> TopSongCard(state.stats)
        CardId.HOURS -> HoursCard(state.stats)
        else -> Text(id.name)
    }
}

@Composable
internal fun StoryShell(cards: List<CardId>, index: Int, onIndex: (Int) -> Unit, card: @Composable (CardId) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), Arrangement.spacedBy(4.dp)) {
            cards.indices.forEach { i ->
                Box(
                    Modifier.weight(1f).height(3.dp).testTag("segment").clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = if (i <= index) 1f else .18f)),
                )
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().testTag("tap").pointerInput(cards, index) {
                detectTapGestures { at ->
                    val next = if (at.x > size.width / 2) index + 1 else index - 1
                    if (next in cards.indices) onIndex(next)
                }
            },
            Alignment.Center,
        ) { card(cards[index.coerceIn(cards.indices)]) }
    }
}
