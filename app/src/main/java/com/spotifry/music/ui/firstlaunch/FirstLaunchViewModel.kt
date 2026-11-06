package com.spotifry.music.ui.firstlaunch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.MIN_ARTISTS
import com.spotifry.music.data.PickedArtist
import com.spotifry.music.data.STARTER_ARTISTS
import com.spotifry.music.data.Settings
import com.spotifry.music.data.lookupArtist
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A starter artist: [url] and [photo] are filled in when the lookup answers, and stay empty offline. */
data class Starter(val name: String, val url: String = "", val photo: String? = null) {
    fun picked() = PickedArtist(name, url, photo)
}

sealed interface ArtistSearch {
    data object Idle : ArtistSearch
    data object Loading : ArtistSearch
    data class Results(val list: List<PageLink>) : ArtistSearch
    data object Error : ArtistSearch
}

/**
 * Screen 1 (pick artists) and the step number of the two screens. [reopen]: opened again from an empty Home; it starts
 * from the artists already picked and has no second screen.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class FirstLaunchViewModel(
    private val source: MusicSource,
    private val settings: Settings,
    val reopen: Boolean = false,
) : ViewModel() {
    val step = MutableStateFlow(1)
    val picked = MutableStateFlow(if (reopen) settings.artists.value else emptyList())
    val query = MutableStateFlow("")
    val starters = MutableStateFlow(STARTER_ARTISTS.map { Starter(it) })

    /** No starter's photo could be fetched, so the phone is most likely offline. */
    val offline = MutableStateFlow(false)
    private val retry = MutableStateFlow(0)

    val search: StateFlow<ArtistSearch> = combine(query.debounce(400), retry) { q, _ -> q.trim() }
        .flatMapLatest { q ->
            flow {
                if (q.none { it.isLetterOrDigit() }) { emit(ArtistSearch.Idle); return@flow }
                emit(ArtistSearch.Loading)
                try { emit(ArtistSearch.Results(source.searchPages(q, SearchType.ARTISTS))) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { emit(ArtistSearch.Error) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ArtistSearch.Idle)

    init { loadPhotos() }

    /** Photos and pages for the starter list: three requests at a time, once when the screen opens (and on "Try again"). */
    private fun loadPhotos() {
        viewModelScope.launch {
            val gate = Semaphore(PHOTOS_AT_ONCE)
            val failed = MutableStateFlow(0)
            var answered = 0
            starters.value.filter { it.url.isEmpty() }.map { s ->
                async {
                    gate.withPermit {
                        try {
                            val link = source.lookupArtist(s.name)
                            answered++
                            if (link != null) starters.value = starters.value.map { if (it.name == s.name) Starter(s.name, link.url, link.thumbnailUrl) else it }
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { failed.value++ }
                    }
                }
            }.awaitAll()
            offline.value = failed.value > 0 && answered == 0
        }
    }

    fun retryPhotos() { offline.value = false; loadPhotos(); retry.value++ }

    fun toggle(a: PickedArtist) {
        picked.value = if (picked.value.any { it.key == a.key }) picked.value.filterNot { it.key == a.key } else picked.value + a
    }

    val canContinue get() = picked.value.size >= MIN_ARTISTS

    /** "Continue": the picks are kept now, so closing the app between the screens doesn't lose them. */
    fun next() {
        if (!canContinue) return
        settings.setArtists(picked.value)
        if (!reopen) step.value = 2
    }

    /** "Skip" on screen 1 keeps nothing and goes on to the link screen. */
    fun skipArtists() { if (!reopen) step.value = 2 }

    fun back() { if (step.value == 2) step.value = 1 }

    /** The end of the first launch, by finishing or skipping the link screen. */
    fun finish() { settings.markFirstLaunchSeen() }

    private companion object { const val PHOTOS_AT_ONCE = 3 }
}
