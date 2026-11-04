package com.spotifry.music.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.db.PlaylistDao
import com.spotifry.music.data.db.PlaylistWithCounts
import com.spotifry.music.data.db.SongDao
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.SongPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistsViewModel(private val dao: PlaylistDao, private val songs: SongDao, private val player: SongPlayer) : ViewModel() {
    val playlists: StateFlow<List<PlaylistWithCounts>> = dao.observePlaylists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** First four songs of each playlist, keyed by playlist id. */
    val covers: StateFlow<Map<Long, List<SongEntity>>> = dao.observeCoverRows()
        .map { rows -> rows.groupBy({ it.playlistId }, { it.song }).mapValues { it.value.take(4) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun songsOf(playlistId: Long): Flow<List<SongEntity>> = dao.observeSongs(playlistId)

    fun create(name: String, thenAdd: Song? = null) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val id = dao.create(clean)
            if (thenAdd != null) addSongNow(id, thenAdd)
        }
    }
    fun rename(id: Long, name: String) { if (name.isNotBlank()) viewModelScope.launch { dao.rename(id, name.trim()) } }
    fun delete(id: Long) { viewModelScope.launch { dao.delete(id) } }
    fun addSong(playlistId: Long, song: Song) { viewModelScope.launch { addSongNow(playlistId, song) } }
    fun removeSong(playlistId: Long, songId: String) { viewModelScope.launch { dao.removeSong(playlistId, songId) } }
    fun play(list: List<Song>, index: Int, from: String? = null) = player.playList(list, index, from)

    private suspend fun addSongNow(playlistId: Long, song: Song) {
        songs.upsert(song.toEntity())
        dao.addSong(playlistId, song.id)
    }
}
