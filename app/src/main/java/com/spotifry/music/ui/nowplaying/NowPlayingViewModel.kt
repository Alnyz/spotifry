package com.spotifry.music.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.DownloadRepository
import com.spotifry.music.data.DownloadState
import com.spotifry.music.data.db.SongDao
import com.spotifry.music.player.MusicPlayer
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flow
import com.spotifry.music.data.Lyrics
import com.spotifry.music.data.source.isRadio
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
    val player: MusicPlayer,
    private val downloads: DownloadRepository,
    songs: SongDao,
    private val lyricsSource: LyricsSource? = null,
    private val notes: com.spotifry.music.data.db.SongNoteDao? = null,
    private val now: () -> Long = System::currentTimeMillis,
    /** The lyrics source picked per song id (Settings.lyricsPick). */
    private val picks: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap()),
) : ViewModel() {
    val state: StateFlow<PlayerUiState> = player.state

    private val savedIds = songs.observeSaved().map { l -> l.map { it.id }.toSet() }

    val save: StateFlow<SaveUi> = combine(
        player.state.map { it.current?.id }.distinctUntilChanged().flatMapLatest { id -> if (id == null) flowOf(DownloadState.Idle) else downloads.state(id) },
        savedIds, player.state.map { it.current?.id }.distinctUntilChanged(),
    ) { dl, saved, id -> if (id == null) SaveUi.Unsaved else saveUi(id, saved, dl) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SaveUi.Unsaved)

    private val lyricsRetry = MutableStateFlow(0)

    private val pickNow = combine(player.state.map { it.current?.id }.distinctUntilChanged(), picks) { id, all -> id?.let(all::get) }.distinctUntilChanged()
    private var lyricsFor: String? = null

    /** Lyrics for the song playing now; looked up again when the song changes, after Try again, or when its source pick changes. */
    val lyrics: StateFlow<LyricsUi> = combine(player.state.map { it.current }.distinctUntilChanged { a, b -> a?.id == b?.id }, lyricsRetry, pickNow) { s, _, pick -> s to pick }
        .flatMapLatest { (song, pick) ->
            flow {
                val src = lyricsSource
                if (song == null || src == null || song.isRadio) { lyricsFor = null; emit(LyricsUi.None); return@flow }
                // switching source on the same song keeps the old lyrics up until the new ones are there
                if (song.id != lyricsFor) emit(LyricsUi.Loading)
                lyricsFor = song.id
                emit(try { src.get(song, pick)?.let { LyricsUi.Ready(it) } ?: LyricsUi.None } catch (e: CancellationException) { throw e } catch (e: Exception) { android.util.Log.w("Lyrics", "couldn't load lyrics", e); LyricsUi.Failed })
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LyricsUi.None)

    /** One lyrics source in the Lyrics source sheet: [lyrics] is what it has for the song, null when nothing; [checking] until it answers. */
    data class SourceRow(val name: String, val lyrics: Lyrics?, val checking: Boolean)

    private val _sources = MutableStateFlow<List<SourceRow>>(emptyList())
    /** What each lyrics source has for the song playing now, filling in as they answer. Filled by [checkSources]. */
    val sources: StateFlow<List<SourceRow>> = _sources
    private var checking: Job? = null

    /** Asks every source in use for the song playing now, all at once; the sheet fills in as each answers. */
    fun checkSources() {
        val song = player.state.value.current ?: return
        val src = lyricsSource ?: return
        checking?.cancel()
        val names = src.enabledSources()
        _sources.value = names.map { SourceRow(it, null, true) }
        checking = viewModelScope.launch {
            names.forEach { name ->
                launch {
                    val found = try { src.from(song, name) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                    _sources.update { rows -> rows.map { if (it.name == name) SourceRow(name, found, false) else it } }
                }
            }
        }
    }

    fun retryLyrics() { lyricsRetry.value++ }

    /** The listener's note on the song playing now, or null. */
    val note: StateFlow<String?> = player.state.map { it.current?.id }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null || notes == null) flowOf(null) else notes.observe(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Saves [raw] as [songId]'s note, cleaned ([com.spotifry.music.data.cleanNote]); empty deletes it. */
    fun setNote(songId: String, raw: String) {
        val dao = notes ?: return
        val text = com.spotifry.music.data.cleanNote(raw)
        viewModelScope.launch { if (text.isEmpty()) dao.delete(songId) else dao.put(com.spotifry.music.data.db.SongNoteEntity(songId, text, now())) }
    }

    /** Tapping only starts or retries a save. Removing a saved song lives in the Saved list menu. */
    fun onSave() {
        val song = player.state.value.current ?: return
        if (save.value is SaveUi.Unsaved || save.value is SaveUi.Failed) viewModelScope.launch { downloads.save(song) }
    }
}
