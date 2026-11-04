package com.spotifry.music.ui.saved

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.DownloadRepository
import com.spotifry.music.data.db.SongDao
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.SongPlayer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import com.spotifry.music.data.db.toEntity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SavedViewModel(songs: SongDao, private val downloads: DownloadRepository, private val player: SongPlayer) : ViewModel() {
    /** Saved songs, plus the ones whose download is still running, so a tap on Save shows up at once. */
    val saved: StateFlow<List<SongEntity>> = combine(songs.observeSaved(), downloads.pending) { done, pending ->
        val doneIds = done.map { it.id }.toSet()
        pending.filter { it.id !in doneIds }.map { it.toEntity() } + done
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun play(list: List<Song>, index: Int) = player.playList(list, index, "Saved")
    fun save(song: Song) { viewModelScope.launch { downloads.save(song) } }
    fun remove(songId: String) { viewModelScope.launch { downloads.remove(songId) } }
}
