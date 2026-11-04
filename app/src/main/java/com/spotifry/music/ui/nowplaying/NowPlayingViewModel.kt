package com.spotifry.music.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.DownloadRepository
import com.spotifry.music.data.DownloadState
import com.spotifry.music.data.db.SongDao
import com.spotifry.music.player.PlayerController
import com.spotifry.music.player.PlayerUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import com.spotifry.music.data.Lyrics
import com.spotifry.music.data.LyricsSource

fun saveUi(songId: String, savedIds: Set<String>, download: DownloadState): SaveUi = when {
    songId in savedIds -> SaveUi.Saved
    download is DownloadState.Downloading -> SaveUi.Saving(download.progress)
    download is DownloadState.Failed -> SaveUi.Failed
    else -> SaveUi.Unsaved
}

@OptIn(ExperimentalCoroutinesApi::class)
sealed interface LyricsUi {
    data object None : LyricsUi
    data object Loading : LyricsUi
    data object Failed : LyricsUi
    data class Ready(val lyrics: Lyrics) : LyricsUi
}

class NowPlayingViewModel(
    val player: PlayerController,
    private val downloads: DownloadRepository,
    songs: SongDao,
    private val lyricsSource: LyricsSource? = null,
) : ViewModel() {
    val state: StateFlow<PlayerUiState> = player.state

    private val savedIds = songs.observeSaved().map { l -> l.map { it.id }.toSet() }

    val save: StateFlow<SaveUi> = combine(
        player.state.map { it.current?.id }.distinctUntilChanged().flatMapLatest { id -> if (id == null) flowOf(DownloadState.Idle) else downloads.state(id) },
        savedIds, player.state.map { it.current?.id }.distinctUntilChanged(),
    ) { dl, saved, id -> if (id == null) SaveUi.Unsaved else saveUi(id, saved, dl) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SaveUi.Unsaved)

    private val lyricsRetry = MutableStateFlow(0)

    /** Lyrics for the song playing now; looked up again when the song changes or after Try again. */
    val lyrics: StateFlow<LyricsUi> = combine(player.state.map { it.current }.distinctUntilChanged { a, b -> a?.id == b?.id }, lyricsRetry) { s, _ -> s }
        .flatMapLatest { song ->
            flow {
                val src = lyricsSource
                if (song == null || src == null) { emit(LyricsUi.None); return@flow }
                emit(LyricsUi.Loading)
                emit(try { src.get(song)?.let { LyricsUi.Ready(it) } ?: LyricsUi.None } catch (e: CancellationException) { throw e } catch (e: Exception) { android.util.Log.w("Lyrics", "couldn't load lyrics", e); LyricsUi.Failed })
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LyricsUi.None)

    fun retryLyrics() { lyricsRetry.value++ }

    /** Tapping only starts or retries a save. Removing a saved song lives in the Saved list menu. */
    fun onSave() {
        val song = player.state.value.current ?: return
        if (save.value is SaveUi.Unsaved || save.value is SaveUi.Failed) viewModelScope.launch { downloads.save(song) }
    }
}
