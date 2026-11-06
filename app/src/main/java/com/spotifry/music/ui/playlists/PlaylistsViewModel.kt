package com.spotifry.music.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.db.PlaylistDao
import com.spotifry.music.data.db.PlaylistSongEntity
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
import kotlinx.coroutines.flow.first
import com.spotifry.music.data.PlaylistOrder
import com.spotifry.music.data.PlaylistOrders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistsViewModel(
    private val dao: PlaylistDao, private val songs: SongDao, private val player: SongPlayer,
    private val orders: PlaylistOrders = PlaylistOrders(null),
) : ViewModel() {
    /** False until the list has been read, so an empty [playlists] says nothing yet. */
    val playlistsRead = MutableStateFlow(false)
    val playlists: StateFlow<List<PlaylistWithCounts>> = dao.observePlaylists().onEach { playlistsRead.value = true }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** First four songs of each playlist, keyed by playlist id. */
    val covers: StateFlow<Map<Long, List<SongEntity>>> = dao.observeCoverRows()
        .map { rows -> rows.groupBy({ it.playlistId }, { it.song }).mapValues { it.value.take(4) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun orderOf(playlistId: Long): PlaylistOrder = orders.get(playlistId)
    fun setOrder(playlistId: Long, order: PlaylistOrder) = orders.set(playlistId, order)

    /** The playlist's songs in [order]: the one Play follows and the list shows. */
    fun songsOf(playlistId: Long, order: PlaylistOrder = PlaylistOrder.CUSTOM): Flow<List<SongEntity>> =
        (if (order == PlaylistOrder.RECENT) dao.observeSongsNewestFirst(playlistId) else dao.observeSongs(playlistId)).map(order::arrange)

    /** Saves a hand-made order and switches the playlist to it. */
    fun saveOrder(playlistId: Long, songIds: List<String>) {
        orders.set(playlistId, PlaylistOrder.CUSTOM)
        viewModelScope.launch { dao.setOrder(playlistId, songIds) }
    }

    /** Puts your order in a new random order and saves it. Returns the old order, for Undo. */
    suspend fun mixUp(playlistId: Long): List<String> {
        val old = dao.observeSongs(playlistId).first().map { it.id }
        dao.setOrder(playlistId, old.shuffled())
        orders.set(playlistId, PlaylistOrder.CUSTOM)
        return old
    }

    fun create(name: String, thenAdd: Song? = null) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val id = dao.create(clean)
            if (thenAdd != null) addSongNow(id, thenAdd)
        }
    }
    /**
     * A new playlist holding [list] (a song list someone shared, an album), named [name] or "name (2)" if that is
     * taken. [onDone] gets its id and the name it got.
     */
    fun createWith(name: String, list: List<Song>, onDone: (Long, String) -> Unit) {
        viewModelScope.launch {
            val free = com.spotifry.music.data.ShareText.freeName(name.trim(), playlists.value.map { it.name })
            val id = dao.create(free)
            list.forEach { addSongNow(id, it) }
            onDone(id, free)
        }
    }
    fun rename(id: Long, name: String) { if (name.isNotBlank()) viewModelScope.launch { dao.rename(id, name.trim()) } }
    fun delete(id: Long) { viewModelScope.launch { dao.delete(id) } }
    fun addSong(playlistId: Long, song: Song) { viewModelScope.launch { addSongNow(playlistId, song) } }
    fun removeSong(playlistId: Long, songId: String) { viewModelScope.launch { dao.removeSong(playlistId, songId) } }
    /** Ids of the playlists that already hold [songId], for the ticks in "Add to playlist". */
    fun playlistsWith(songId: String): Flow<Set<Long>> = dao.observePlaylistIdsWith(songId).map { it.toSet() }
    /** Takes the song out and returns its row (position included), for Undo; null if it wasn't there. */
    suspend fun takeOut(playlistId: Long, songId: String): PlaylistSongEntity? =
        dao.row(playlistId, songId)?.also { dao.removeSong(playlistId, songId) }
    /** Undo for [takeOut]: the song goes back at the same spot. */
    suspend fun putBack(row: PlaylistSongEntity) = dao.putBack(row)
    fun play(list: List<Song>, index: Int, from: String? = null) = player.playList(list, index, from)

    /** From the start, in the order the playlist's own page shows. */
    fun playAll(p: PlaylistWithCounts) {
        viewModelScope.launch {
            val list = songsOf(p.id, orderOf(p.id)).first().map { it.toSong() }
            if (list.isNotEmpty()) play(list, 0, p.name)
        }
    }
    fun shuffle(list: List<Song>, from: String) = player.shuffleList(list, from)

    private suspend fun addSongNow(playlistId: Long, song: Song) {
        songs.upsert(song.toEntity())
        dao.addSong(playlistId, song.id)
    }
}
