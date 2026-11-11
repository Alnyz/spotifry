package com.spotifry.music.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.SpotifyPlaylist
import com.spotifry.music.data.SpotifyTrack
import com.spotifry.music.data.YouTubeImport
import com.spotifry.music.data.YouTubePlaylist
import com.spotifry.music.data.db.PlaylistDao
import com.spotifry.music.data.db.SongDao
import com.spotifry.music.data.source.MusicSource
import kotlinx.coroutines.flow.StateFlow

/** The phone's handle on [PlaylistImport], which holds the work and is shared with the desktop app. */
class ImportViewModel(
    fetch: suspend (String) -> SpotifyPlaylist,
    source: MusicSource,
    playlists: PlaylistDao,
    songs: SongDao,
    fetchYouTube: suspend (String) -> YouTubePlaylist = YouTubeImport::fetch,
) : ViewModel() {
    private val core = PlaylistImport(viewModelScope, fetch, source, playlists, songs, fetchYouTube)
    val state: StateFlow<ImportState> get() = core.state
    val leftover: StateFlow<ImportState.Done?> get() = core.leftover
    fun open() = core.open()
    fun close() = core.close()
    fun searchFor(track: SpotifyTrack) = core.searchFor(track)
    fun review() = core.review()
    fun cancel() = core.cancel()
    fun retry() = core.retry()
    fun start(link: String) = core.start(link)
}
