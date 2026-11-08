package com.spotifry.music.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.SpotifyImport
import com.spotifry.music.data.SpotifyPlaylist
import com.spotifry.music.data.SpotifyTrack
import com.spotifry.music.data.db.PlaylistDao
import com.spotifry.music.data.db.SongDao
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.MusicSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

sealed interface ImportState {
    data object Closed : ImportState
    data class Input(val error: String? = null) : ImportState
    data object Opening : ImportState
    /** [recent] newest first; a null `found` is the song being looked up now. */
    data class Running(val name: String, val done: Int, val total: Int, val secondsLeft: Long?, val recent: List<Pair<SpotifyTrack, Boolean?>>) : ImportState
    data class Done(val playlistId: Long, val name: String, val added: Int, val total: Int, val missing: List<SpotifyTrack>) : ImportState
    data class Failed(val message: String) : ImportState
}

/** Spotify link in, playlist out. Lives as long as the app shell, so switching tabs does not stop it. */
class ImportViewModel(
    private val fetch: suspend (String) -> SpotifyPlaylist,
    private val source: MusicSource,
    private val playlists: PlaylistDao,
    private val songs: SongDao,
) : ViewModel() {
    private val _state = MutableStateFlow<ImportState>(ImportState.Closed)
    val state: StateFlow<ImportState> = _state
    private var job: Job? = null
    private var importing: Long? = null

    fun open() { if (_state.value == ImportState.Closed) _state.value = ImportState.Input() }

    /** Closing while it runs stops it; the songs found so far stay in the playlist. */
    fun close() { job?.cancel(); job = null; _state.value = ImportState.Closed; _leftover.value = null }

    private val _leftover = MutableStateFlow<ImportState.Done?>(null)
    /** The finished import, minus the songs already sent to Search, so the rest can be searched later. */
    val leftover: StateFlow<ImportState.Done?> = _leftover

    /** Search for one not-found song: the sheet hides, the others stay on the Playlists page (see [review]). */
    fun searchFor(track: SpotifyTrack) {
        val done = _state.value as? ImportState.Done
        close()
        _leftover.value = done?.copy(missing = done.missing - track)?.takeIf { it.missing.isNotEmpty() }
    }

    fun review() { _leftover.value?.let { _state.value = it } }

    /** Stops and removes the playlist it was filling, as if the import never happened. */
    fun cancel() {
        job?.cancel(); job = null
        val id = importing
        _state.value = ImportState.Closed
        if (id != null) viewModelScope.launch { withContext(NonCancellable) { playlists.delete(id) } }
    }

    fun retry() { _state.value = ImportState.Input() }

    fun start(link: String) {
        val id = SpotifyImport.playlistId(link) ?: run { _state.value = ImportState.Input("That isn't a Spotify playlist link"); return }
        job = viewModelScope.launch {
            _state.value = ImportState.Opening
            val list = try { fetch(id) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.value = ImportState.Failed("Couldn't open that playlist. Check it's public and you're online."); return@launch
            }
            if (list.tracks.isEmpty()) { _state.value = ImportState.Failed("That playlist has no songs."); return@launch }
            run(list)
        }
    }

    private suspend fun run(list: SpotifyPlaylist) {
        val playlistId = playlists.create(list.name)
        importing = playlistId
        val recent = ArrayDeque<Pair<SpotifyTrack, Boolean?>>()
        val missing = mutableListOf<SpotifyTrack>()
        var added = 0
        val started = System.currentTimeMillis()
        var done = 0
        try {
            // 3 YouTube searches at a time: about 3x faster, still a polite rate. Added in Spotify's order.
            list.tracks.chunked(PARALLEL).forEach { group ->
                val left = if (done == 0) null else (System.currentTimeMillis() - started) / done * (list.tracks.size - done) / 1000
                group.forEach { recent.addFirst(it to null) }
                _state.value = ImportState.Running(list.name, done, list.tracks.size, left, recent.take(5))
                // ponytail: a failed search counts as not found; offline mid-import gives a long not-found list
                val found = coroutineScope {
                    group.map { track -> async { try { SpotifyImport.match(source, track) } catch (e: CancellationException) { throw e } catch (e: Exception) { null } } }.awaitAll()
                }
                group.zip(found).forEach { (track, song) ->
                    if (song != null) {
                        songs.upsert(song.toEntity()); playlists.addSong(playlistId, song.id); added++
                    } else missing += track
                    recent[recent.indexOfFirst { it.first === track && it.second == null }] = track to (song != null)
                }
                done += group.size
                while (recent.size > 5) recent.removeLast()
            }
        } finally {
            if (added == 0) withContext(NonCancellable) { playlists.delete(playlistId) }
        }
        importing = null
        _state.value = if (added == 0) ImportState.Failed("None of the songs were found on YouTube. Check you're online, then try again.")
        else ImportState.Done(playlistId, list.name, added, list.tracks.size, missing)
    }

    private companion object { const val PARALLEL = 3 }
}
