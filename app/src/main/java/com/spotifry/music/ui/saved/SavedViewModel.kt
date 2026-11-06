package com.spotifry.music.ui.saved

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.DownloadRepository
import com.spotifry.music.data.LocalMusic
import com.spotifry.music.data.db.HistoryDao
import com.spotifry.music.data.db.SongDao
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.SongPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import com.spotifry.music.data.db.toEntity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SavedViewModel(songs: SongDao, private val downloads: DownloadRepository, private val player: SongPlayer, private val local: LocalMusic, history: HistoryDao) : ViewModel() {
    /** Saved songs, plus the ones whose download is still running, so a tap on Save shows up at once. */
    val saved: StateFlow<List<SongEntity>> = combine(songs.observeSaved(), downloads.pending) { done, pending ->
        val doneIds = done.map { it.id }.toSet()
        pending.filter { it.id !in doneIds }.map { it.toEntity() } + done
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Music files already on the phone; also feeds search, so read once at start. */
    val phone: StateFlow<List<Song>> = local.songs
    /** Kept here, not in the screen, so it survives going to another tab and back. */
    private val filter = MutableStateFlow(SavedFilter.All)
    /** The list for the picked filter, worked out off the main thread: the phone's list can be thousands of songs. */
    val view: StateFlow<SavedView> = combine(filter, saved, phone, history.observeRecent()) { f, s, p, r -> SavedView(f, filterSaved(f, s, p, r)) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, SavedView())
    init { refreshPhone() }

    fun pick(f: SavedFilter) { filter.value = f }

    fun allowed() = local.allowed()
    fun refreshPhone() { viewModelScope.launch { local.refresh() } }
    /** The queue is the list as filtered, in the order shown. */
    fun play(list: List<Song>, index: Int) = player.playList(list, index, view.value.filter.queueName)
    fun shuffle(list: List<Song>) = player.shuffleList(list, view.value.filter.queueName)
    fun save(song: Song) { viewModelScope.launch { downloads.save(song) } }
    fun remove(songId: String) { viewModelScope.launch { downloads.remove(songId) } }
}
