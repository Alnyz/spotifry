package com.spotifry.music.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** The listener's own short note on a song, already cleaned ([com.spotifry.music.data.cleanNote]). [editedAt] decides which note wins a restore. */
@Entity(tableName = "song_notes")
data class SongNoteEntity(@PrimaryKey val songId: String, val text: String, val editedAt: Long)

@Dao
interface SongNoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(e: SongNoteEntity)
    @Query("DELETE FROM song_notes WHERE songId = :songId") suspend fun delete(songId: String)
    @Query("SELECT text FROM song_notes WHERE songId = :songId") fun observe(songId: String): Flow<String?>
}
