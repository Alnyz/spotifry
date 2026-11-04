package com.spotifry.music.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.RecentSearches
import com.spotifry.music.data.db.HistoryDao
import com.spotifry.music.data.db.SongDao
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.SongPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Results(val songs: List<Song>) : SearchState
    data class Error(val query: String) : SearchState
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val source: MusicSource,
    private val history: HistoryDao,
    private val songs: SongDao,
    private val player: SongPlayer,
    val recentSearches: RecentSearches = RecentSearches(emptyList()),
    mostPlayed: Flow<List<SongEntity>> = flowOf(emptyList()),
    localSongs: Flow<List<Song>> = flowOf(emptyList()),
) : ViewModel() {
    val mostPlayed: StateFlow<List<SongEntity>> = mostPlayed.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val query = MutableStateFlow("")
    private val retryTick = MutableStateFlow(0)
    val currentQuery: String get() = query.value
    /** What the search box shows. Lives here, not in the screen, so the Playlists tab can search for a song. */
    val queryText: StateFlow<String> = query
    /** Up to three music files on the phone that match the search, shown above the YouTube results. */
    val localResults: StateFlow<List<Song>> = combine(query, localSongs) { q, l -> com.spotifry.music.data.localMatches(l, q) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val search: StateFlow<SearchState> = combine(query.debounce(400), retryTick) { q, _ -> q.trim() }
        .flatMapLatest { q ->
            flow {
                // nothing searchable (empty, spaces, only symbols): no request and no error
                if (q.none { it.isLetterOrDigit() }) { emit(SearchState.Idle); return@flow }
                emit(SearchState.Loading)
                try {
                    emit(SearchState.Results(source.search(q)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emit(SearchState.Error(q))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchState.Idle)

    val recent: StateFlow<List<SongEntity>> = history.observeRecent()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val saved: StateFlow<List<SongEntity>> = songs.observeSaved()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val savedIds: StateFlow<Set<String>> = saved.map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun onQueryChanged(text: String) { query.value = text }
    fun retry() { retryTick.value++ }

    fun play(list: List<Song>, index: Int, from: String) = player.playList(list, index, from)

    /** Playing a result is what makes a search worth keeping, so half-typed words never land in the history. */
    fun playFromSearch(list: List<Song>, index: Int) {
        recentSearches.add(query.value)
        play(list, index, "Search “${query.value.trim()}”")
    }

    /** The keyboard's search key: keeps the words and searches again. */
    fun submit() { recentSearches.add(query.value); retry() }

    /** Every saved song, shuffled. */
    fun shuffleSaved() {
        val list = saved.value.map { it.toSong() }
        if (list.isNotEmpty()) player.shuffleList(list, "Saved")
    }
}
