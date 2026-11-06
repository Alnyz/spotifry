package com.spotifry.music.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.ArtistSongs
import com.spotifry.music.data.PickedArtist
import com.spotifry.music.data.Settings
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** What Home shows for the picked artists: the artists at once, their songs when the pages have answered. */
data class ArtistsHome(val artists: List<PickedArtist> = emptyList(), val songs: List<Song> = emptyList())

class ArtistsHomeViewModel(private val settings: Settings, private val loader: ArtistSongs) : ViewModel() {
    private val _state = MutableStateFlow(ArtistsHome(settings.artists.value))
    val state: StateFlow<ArtistsHome> = _state
    private val again = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(settings.artists, again) { a, _ -> a }.collectLatest { list ->
                _state.value = ArtistsHome(list, _state.value.songs.takeIf { list.isNotEmpty() }.orEmpty())
                if (list.isEmpty()) return@collectLatest
                val got = loader.load(list)
                // an artist picked offline now has its page: keep it, so it isn't looked up again
                if (got.artists != list) settings.setArtists(got.artists)
                _state.value = ArtistsHome(got.artists, got.songs)
            }
        }
    }

    /** Home was opened: costs no request while the answers are fresh. */
    fun refresh() { again.value++ }
}
