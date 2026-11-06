package com.spotifry.music.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.ArtistSongs
import com.spotifry.music.data.PickedArtist
import com.spotifry.music.data.Settings
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What Home shows for the picked artists: the artists at once, their songs when the pages have answered. */
data class ArtistsHome(val artists: List<PickedArtist> = emptyList(), val songs: List<Song> = emptyList(), val loading: Boolean = false)

class ArtistsHomeViewModel(private val settings: Settings, private val loader: ArtistSongs) : ViewModel() {
    private val _state = MutableStateFlow(ArtistsHome(settings.artists.value))
    val state: StateFlow<ArtistsHome> = _state
    private var job: Job? = null

    init {
        viewModelScope.launch {
            settings.artists.collect { list ->
                loader.keepOnly(list)
                _state.value = ArtistsHome(list, loader.known(list))
                load(list)
            }
        }
    }

    private fun load(list: List<PickedArtist>) {
        job?.cancel()
        if (list.isEmpty()) return
        if (_state.value.songs.isEmpty()) _state.value = _state.value.copy(loading = true)
        job = viewModelScope.launch {
            val got = loader.load(list)
            // an artist picked offline now has its page: keep it, so it isn't looked up again
            // only if the picks are still the ones this load started from: an edit saved meanwhile is not overwritten
            if (got.artists != list && settings.artists.value == list) settings.setArtists(got.artists)
            _state.value = ArtistsHome(got.artists, got.songs)
        }
    }

    /** Home was opened: nothing while a load is running, and no request while the answers are fresh. */
    fun refresh() { if (job?.isActive != true) load(settings.artists.value) }
}
