package com.spotifry.music.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SongDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertIgnore(e: SongEntity)
    @Query("UPDATE songs SET title = :title, artist = :artist, durationSec = :durationSec, thumbnailUrl = :thumbnailUrl WHERE id = :id")
    protected abstract suspend fun updateMeta(id: String, title: String, artist: String, durationSec: Long, thumbnailUrl: String?)

    /** Insert or refresh metadata. Never touches the saved columns. */
    @Transaction open suspend fun upsert(e: SongEntity) {
        insertIgnore(e)
        updateMeta(e.id, e.title, e.artist, e.durationSec, e.thumbnailUrl)
    }

    @Query("SELECT * FROM songs WHERE filePath IS NOT NULL ORDER BY savedAt DESC") abstract fun observeSaved(): Flow<List<SongEntity>>
    @Query("SELECT * FROM songs WHERE id = :id") abstract suspend fun get(id: String): SongEntity?
    @Query("SELECT COUNT(*) FROM songs WHERE filePath IS NOT NULL") abstract suspend fun savedCount(): Int
    @Query("UPDATE songs SET filePath = :filePath, thumbPath = :thumbPath, savedAt = :savedAt WHERE id = :id")
    abstract suspend fun markSaved(id: String, filePath: String, thumbPath: String?, savedAt: Long)
    @Query("UPDATE songs SET filePath = NULL, thumbPath = NULL, savedAt = NULL WHERE id = :id") abstract suspend fun clearSaved(id: String)
}

@Dao
abstract class PlaylistDao {
    @Insert protected abstract suspend fun insert(p: PlaylistEntity): Long
    suspend fun create(name: String): Long = insert(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))

    @Query("UPDATE playlists SET name = :name WHERE id = :id") abstract suspend fun rename(id: Long, name: String)
    @Query("DELETE FROM playlists WHERE id = :id") abstract suspend fun delete(id: Long)

    @Query(
        "INSERT OR IGNORE INTO playlist_songs (playlistId, songId, position, addedAt) " +
            "SELECT :playlistId, :songId, COALESCE(MAX(position), -1) + 1, :addedAt FROM playlist_songs WHERE playlistId = :playlistId"
    )
    protected abstract suspend fun insertSong(playlistId: Long, songId: String, addedAt: Long)
    suspend fun addSong(playlistId: Long, songId: String) = insertSong(playlistId, songId, System.currentTimeMillis())

    @Query("UPDATE playlist_songs SET position = :position WHERE playlistId = :playlistId AND songId = :songId")
    protected abstract suspend fun setPosition(playlistId: Long, songId: String, position: Int)
    /** Saves a new hand-made order: [songIds] top to bottom. */
    @androidx.room.Transaction
    open suspend fun setOrder(playlistId: Long, songIds: List<String>) { songIds.forEachIndexed { i, id -> setPosition(playlistId, id, i) } }
    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    abstract suspend fun removeSong(playlistId: Long, songId: String)

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    abstract suspend fun row(playlistId: Long, songId: String): PlaylistSongEntity?
    @Query("UPDATE playlist_songs SET position = position + 1 WHERE playlistId = :playlistId AND position >= :position")
    protected abstract suspend fun makeRoomAt(playlistId: Long, position: Int)
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertRow(row: PlaylistSongEntity)
    /** Puts a removed [row] back at its old position, moving the songs from there down one. */
    @Transaction
    open suspend fun putBack(row: PlaylistSongEntity) {
        if (row(row.playlistId, row.songId) != null) return
        makeRoomAt(row.playlistId, row.position)
        insertRow(row)
    }

    /** Every song that is in at least one playlist, for the mark on song rows. */
    @Query("SELECT DISTINCT songId FROM playlist_songs")
    abstract fun observeSongIdsInPlaylists(): Flow<List<String>>

    /** Ids of the playlists that hold [songId]. */
    @Query("SELECT playlistId FROM playlist_songs WHERE songId = :songId")
    abstract fun observePlaylistIdsWith(songId: String): Flow<List<Long>>

    @Query(
        "SELECT p.id AS id, p.name AS name, " +
            "(SELECT COUNT(*) FROM playlist_songs ps WHERE ps.playlistId = p.id) AS songCount, " +
            "(SELECT COUNT(*) FROM playlist_songs ps JOIN songs s ON s.id = ps.songId WHERE ps.playlistId = p.id AND s.filePath IS NOT NULL) AS savedCount, " +
            "p.id AS number " +
            "FROM playlists p ORDER BY p.id"
    )
    abstract fun observePlaylists(): Flow<List<PlaylistWithCounts>>

    @Query("SELECT s.* FROM songs s JOIN playlist_songs ps ON ps.songId = s.id WHERE ps.playlistId = :playlistId ORDER BY ps.position")
    abstract fun observeSongs(playlistId: Long): Flow<List<SongEntity>>

    @Query("SELECT s.* FROM songs s JOIN playlist_songs ps ON ps.songId = s.id WHERE ps.playlistId = :playlistId ORDER BY ps.addedAt DESC, ps.position DESC")
    abstract fun observeSongsNewestFirst(playlistId: Long): Flow<List<SongEntity>>

    /** Every playlist's songs in order, for the cover squares on the Playlists screen. */
    @Query("SELECT ps.playlistId AS playlistId, s.* FROM playlist_songs ps JOIN songs s ON s.id = ps.songId ORDER BY ps.playlistId, ps.position")
    abstract fun observeCoverRows(): Flow<List<PlaylistCoverRow>>
}

@Dao
abstract class HistoryDao {
    @Upsert protected abstract suspend fun upsert(e: HistoryEntity)
    @Query("DELETE FROM history WHERE songId NOT IN (SELECT songId FROM history ORDER BY playedAt DESC LIMIT 20)")
    protected abstract suspend fun prune()

    @Transaction open suspend fun record(songId: String, playedAt: Long) { upsert(HistoryEntity(songId, playedAt)); prune() }

    @Query("DELETE FROM history") abstract suspend fun clear()

    @Query("SELECT s.* FROM songs s JOIN history h ON h.songId = s.id ORDER BY h.playedAt DESC")
    abstract fun observeRecent(): Flow<List<SongEntity>>
}

@Dao
abstract class PlayCountDao {
    @Query("INSERT OR IGNORE INTO plays (songId, count, lastPlayedAt) VALUES (:songId, 0, :at)")
    protected abstract suspend fun ensure(songId: String, at: Long)
    @Query("UPDATE plays SET count = count + 1, lastPlayedAt = :at WHERE songId = :songId")
    protected abstract suspend fun bump(songId: String, at: Long)

    @Transaction open suspend fun count(songId: String, at: Long) { ensure(songId, at); bump(songId, at) }

    @Query("DELETE FROM plays") abstract suspend fun clear()

    /** How many times each of [ids] was played; songs never played are left out. */
    @Query("SELECT songId, count AS n FROM plays WHERE songId IN (:ids) AND count > 0") abstract suspend fun counts(ids: List<String>): List<PlayCountRow>

    @Query("SELECT songId FROM plays WHERE lastPlayedAt >= :since") abstract suspend fun playedSince(since: Long): List<String>

    /** Ties go to the song played most recently. */
    @Query("SELECT s.* FROM songs s JOIN plays p ON p.songId = s.id ORDER BY p.count DESC, p.lastPlayedAt DESC LIMIT :limit")
    abstract fun observeMostPlayed(limit: Int): Flow<List<SongEntity>>
}

/** Reads everything for a backup, and merges one in: never overwrites newer data, and doing it twice changes nothing. */
@Dao
abstract class BackupDao {
    @Query("SELECT * FROM songs") abstract suspend fun songs(): List<SongEntity>
    @Query("SELECT id FROM songs WHERE filePath IS NOT NULL") abstract suspend fun savedIds(): List<String>
    @Query("SELECT * FROM playlists ORDER BY id") abstract suspend fun playlists(): List<PlaylistEntity>
    @Query("SELECT * FROM playlist_songs ORDER BY playlistId, position") abstract suspend fun playlistSongs(): List<PlaylistSongEntity>
    @Query("SELECT * FROM history") abstract suspend fun history(): List<HistoryEntity>
    @Query("SELECT * FROM plays") abstract suspend fun plays(): List<PlayCountEntity>
    @Query("SELECT * FROM listens") abstract suspend fun listens(): List<ListenEntity>
    @Query("SELECT * FROM song_notes") abstract suspend fun notes(): List<SongNoteEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertSong(e: SongEntity)
    @Insert protected abstract suspend fun insertPlaylist(p: PlaylistEntity): Long
    @Query(
        "INSERT OR IGNORE INTO playlist_songs (playlistId, songId, position, addedAt) " +
            "SELECT :playlistId, :songId, COALESCE(MAX(position), -1) + 1, :addedAt FROM playlist_songs WHERE playlistId = :playlistId"
    )
    protected abstract suspend fun appendSong(playlistId: Long, songId: String, addedAt: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertHistory(e: HistoryEntity)
    @Query("UPDATE history SET playedAt = MAX(playedAt, :at) WHERE songId = :songId") protected abstract suspend fun laterHistory(songId: String, at: Long)
    @Query("DELETE FROM history WHERE songId NOT IN (SELECT songId FROM history ORDER BY playedAt DESC LIMIT 20)") protected abstract suspend fun pruneHistory()
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertPlays(e: PlayCountEntity)
    @Query("UPDATE plays SET count = MAX(count, :count), lastPlayedAt = MAX(lastPlayedAt, :at) WHERE songId = :songId")
    protected abstract suspend fun largerPlays(songId: String, count: Int, at: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertListen(e: ListenEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertNote(e: SongNoteEntity)
    @Query("UPDATE song_notes SET text = :text, editedAt = :at WHERE songId = :songId AND editedAt < :at")
    protected abstract suspend fun newerNote(songId: String, text: String, at: Long)

    /** Returns this phone's id for each of the backup's playlists, in order, and whether the merge made it. */
    @Transaction
    open suspend fun merge(b: com.spotifry.music.data.BackupFile): List<Pair<Long, Boolean>> {
        b.songs.forEach { insertSong(it.copy(savedAt = null, filePath = null, thumbPath = null)) }
        // the nth playlist of a name is the nth one here, so two with the same name stay two
        val here = playlists().groupBy({ it.name }, { it.id }).mapValues { ArrayDeque(it.value) }
        val ids = b.playlists.map { p ->
            val found = here[p.name]?.removeFirstOrNull()
            val id = found ?: insertPlaylist(PlaylistEntity(name = p.name, createdAt = p.createdAt))
            p.songs.forEach { appendSong(id, it.songId, it.addedAt) }
            id to (found == null)
        }
        b.history.forEach { insertHistory(it); laterHistory(it.songId, it.playedAt) }
        pruneHistory()
        // the larger count, not the sum: both phones may hold the same plays
        b.plays.forEach { insertPlays(it); largerPlays(it.songId, it.count, it.lastPlayedAt) }
        b.listens.forEach { insertListen(it) }
        // the note written last wins, here or in the backup
        b.notes.forEach { insertNote(it); newerNote(it.songId, it.text, it.editedAt) }
        return ids
    }
}

@Dao
abstract class ListenDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insert(e: ListenEntity)
    /** Only ever an update: after Clear listening history the rest of a song already playing isn't brought back. */
    @Query("UPDATE listens SET secondsHeard = :seconds, skipped = :skipped WHERE startedAt = :startedAt")
    abstract suspend fun update(startedAt: Long, seconds: Int, skipped: Boolean)
    @Query("DELETE FROM listens") abstract suspend fun clear()
    @Query("SELECT l.songId, COUNT(*) AS n FROM listens l JOIN songs s ON s.id = l.songId WHERE l.secondsHeard >= 30 GROUP BY l.songId")
    abstract suspend fun playCounts(): List<PlayCountRow>

    @Query("SELECT l.startedAt, l.songId, l.secondsHeard, l.skipped, s.* FROM listens l LEFT JOIN songs s ON s.id = l.songId ORDER BY l.startedAt")
    abstract fun observeAll(): Flow<List<ListenRow>>
    @Query("SELECT * FROM listens WHERE startedAt >= :since") abstract fun observeSince(since: Long): Flow<List<ListenEntity>>
    @Query("SELECT EXISTS(SELECT 1 FROM listens)") abstract fun observeAny(): Flow<Boolean>
}
