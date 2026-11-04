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

    /** Ties go to the song played most recently. */
    @Query("SELECT s.* FROM songs s JOIN plays p ON p.songId = s.id ORDER BY p.count DESC, p.lastPlayedAt DESC LIMIT :limit")
    abstract fun observeMostPlayed(limit: Int): Flow<List<SongEntity>>
}
