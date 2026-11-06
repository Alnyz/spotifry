package com.spotifry.music.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.ArtistSongs
import com.spotifry.music.data.PickedArtist
import com.spotifry.music.data.Settings
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What Home shows for the picked artists: the artists at once, their songs when the pages have answered. */
data class ArtistsHome(val artists: List<PickedArtist> = emptyList(), val songs: List<Song> = emptyList(), val loading: Boolean = false)

class ArtistsHomeViewModel(private val settings: Settings, private val loader: ArtistSongs) : ViewModel() {
    private val _state = MutableStateFlow(settings.artists.value.let { ArtistsHome(it, loader.known(it), loading = it.isNotEmpty() && loader.known(it).isEmpty()) })
    val state: StateFlow<ArtistsHome> = _state
    private var job: Job? = null
    private var giveUp: Job? = null

    init {
        viewModelScope.launch {
            settings.artists.collect { list ->
                loader.keepOnly(list)
                val known = loader.known(list)
                _state.value = ArtistsHome(list, known, loading = list.isNotEmpty() && known.isEmpty())
                load(list)
            }
        }
    }

    private fun load(list: List<PickedArtist>) {
        job?.cancel(); giveUp?.cancel()
        if (list.isEmpty()) return
        if (_state.value.songs.isEmpty()) {
            _state.value = _state.value.copy(loading = true)
            // a slow or dead connection doesn't keep the grey shapes up for good; the songs still show if they arrive later
            giveUp = viewModelScope.launch { delay(SHAPES_MAX_MS); _state.value = _state.value.copy(loading = false) }
        }
        job = viewModelScope.launch {
            val got = loader.load(list)
            // an artist picked offline now has its page: keep it, so it isn't looked up again
            // only if the picks are still the ones this load started from: an edit saved meanwhile is not overwritten
            if (got.artists != list && settings.artists.value == list) settings.setArtists(got.artists)
            giveUp?.cancel()
            _state.value = ArtistsHome(got.artists, got.songs)
        }
    }

    private companion object { const val SHAPES_MAX_MS = 10_000L }

    /** Home was opened: asks again only for an artist whose page failed before; the ones already picked are kept as they are. */
    fun refresh() { if (job?.isActive != true && loader.missing(settings.artists.value)) load(settings.artists.value) }
}
