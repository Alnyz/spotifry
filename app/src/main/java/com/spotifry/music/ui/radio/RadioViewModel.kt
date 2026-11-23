package com.spotifry.music.ui.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.RadioFavourites
import com.spotifry.music.data.source.RadioBrowser
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.RadioStation
import com.spotifry.music.data.source.Song
import com.spotifry.music.ui.home.Mood
import com.spotifry.music.ui.home.moodLists
import com.spotifry.music.data.source.toSong
import com.spotifry.music.player.SongPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface StationsUi {
    data object Loading : StationsUi
    data object Failed : StationsUi
    data class Ready(val stations: List<RadioStation>) : StationsUi
}

/** Every country, by name in the phone's language, with the code radio-browser.info uses. A country with no stations just shows an empty list. */
val RADIO_COUNTRIES: List<Pair<String, String>> = java.util.Locale.getISOCountries()
    .map { java.util.Locale.Builder().setRegion(it).build().displayCountry to it }
    .filter { (name, code) -> name.isNotBlank() && name != code }
    .sortedBy { it.first }

/** Genres offered in the filter: the tag stations are listed under. */
val RADIO_GENRES = listOf("pop", "rock", "hip hop", "dance", "electronic", "jazz", "classical", "country", "chill", "news")

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class RadioViewModel(
    private val browser: RadioBrowser, val favourites: RadioFavourites, private val player: SongPlayer,
    private val source: MusicSource, forYou: StateFlow<List<Song>?>,
    deviceCountry: String = java.util.Locale.getDefault().country,
) : ViewModel() {
    val query = MutableStateFlow("")
    /** The country code, or null for stations from everywhere. Starts as the phone's own region when the list has it. */
    val country = MutableStateFlow(deviceCountry.takeIf { c -> RADIO_COUNTRIES.any { it.second == c } })
    val genre = MutableStateFlow<String?>(null)
    private val retry = MutableStateFlow(0)

    val stations: StateFlow<StationsUi> = combine(query.debounce { if (it.isEmpty()) 0L else 350L }, country, genre, retry) { q, c, g, _ -> Triple(q, c, g) }
        .flatMapLatest { (q, c, g) ->
            flow {
                emit(StationsUi.Loading)
                emit(try { StationsUi.Ready(browser.search(q, c.orEmpty(), g.orEmpty())) } catch (e: CancellationException) { throw e } catch (e: Exception) { StationsUi.Failed })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StationsUi.Loading)

    /** "Made for you" only when Home has built a For you list from the listener's history. */
    val hasMadeForYou: StateFlow<Boolean> = forYou.map { !it.isNullOrEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val madeForYou = forYou

    /** The name of the mix being found, and the one that couldn't be started. */
    val starting = MutableStateFlow<String?>(null)
    val startFailed = MutableStateFlow<String?>(null)

    fun startMadeForYou() { madeForYou.value?.takeIf { it.isNotEmpty() }?.let { player.playList(it, 0, "Made for you") } }

    /** Songs from the first lists a mood search finds, shuffled; the player adds similar songs when they run low, so it goes on. */
    fun startMix(mood: Mood) {
        if (starting.value != null) return
        starting.value = mood.name; startFailed.value = null
        viewModelScope.launch {
            try {
                val lists = moodLists(source, mood, enough = 2).lists
                val songs = lists.flatMap { link -> try { source.page(link).songs.take(40) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() } }.distinctBy { it.id }
                if (songs.isEmpty()) startFailed.value = mood.name else player.shuffleList(songs, "Nonstop · ${mood.name}")
            } catch (e: CancellationException) { throw e } catch (_: Exception) { startFailed.value = mood.name }
            finally { starting.value = null }
        }
    }

    fun retry() { retry.value++ }
    fun pickGenre(g: String) { genre.value = if (genre.value == g) null else g }
    fun play(s: RadioStation) {
        player.playList(listOf(s.toSong()), 0, "Radio")
        viewModelScope.launch { browser.click(s.uuid) }
    }
}
