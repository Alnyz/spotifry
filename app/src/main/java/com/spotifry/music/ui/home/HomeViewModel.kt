package com.spotifry.music.ui.home

import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.RecentSearches
import com.spotifry.music.data.db.HistoryDao
import com.spotifry.music.data.db.SongDao
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
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
    /** [songs] for Songs and Music videos, [pages] for the other types. */
    data class Results(val songs: List<Song>, val pages: List<PageLink> = emptyList(), val type: SearchType = SearchType.SONGS, val query: String = "") : SearchState
    data class Error(val query: String, val type: SearchType = SearchType.SONGS) : SearchState
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
    /** Fetches the links of songs likely to be tapped next. */
    private val warm: (List<Song>) -> Unit = {},
) : ViewModel() {
    val mostPlayed: StateFlow<List<SongEntity>> = mostPlayed.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val query = MutableStateFlow("")
    private val retryTick = MutableStateFlow(0)
    /** Songs on every fresh start; kept while the app is open. */
    val type = MutableStateFlow(SearchType.SONGS)
    /** Each mood tile's playlists, so opening it again is instant while the app runs. */
    val moodLists = mutableMapOf<String, List<PageLink>>()
    val currentQuery: String get() = query.value
    /** What the search box shows. Lives here, not in the screen, so the Playlists tab can search for a song. */
    val queryText: StateFlow<String> = query
    /** Up to three music files on the phone that match the search, shown above the YouTube results. */
    val localResults: StateFlow<List<Song>> = combine(query, localSongs) { q, l -> com.spotifry.music.data.localMatches(l, q) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val search: StateFlow<SearchState> = combine(query.debounce(400), type, retryTick) { q, t, _ -> q.trim() to t }
        .flatMapLatest { (q, t) ->
            flow {
                // nothing searchable (empty, spaces, only symbols): no request and no error
                if (q.none { it.isLetterOrDigit() }) { emit(SearchState.Idle); return@flow }
                emit(SearchState.Loading)
                try {
                    emit(when (t) {
                        SearchType.SONGS -> SearchState.Results(source.search(q), query = q)
                        SearchType.VIDEOS -> SearchState.Results(source.searchVideos(q), type = t, query = q)
                        else -> SearchState.Results(emptyList(), source.searchPages(q, t), t, q)
                    })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emit(SearchState.Error(q, t))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchState.Idle)

    init {
        // the top songs once the results have settled (not on every pause while typing)
        viewModelScope.launch {
            search.collectLatest { s -> if (s is SearchState.Results && s.songs.isNotEmpty()) { delay(WARM_AFTER_MS); warm(s.songs.take(WARM_COUNT)) } }
        }
    }

    /** Null until the history has been read. */
    val recentOrNull: StateFlow<List<SongEntity>?> = history.observeRecent()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

/** How many of the top search results get their links fetched ahead, and how long the results must stay first. */
private const val WARM_COUNT = 3
private const val WARM_AFTER_MS = 500L
