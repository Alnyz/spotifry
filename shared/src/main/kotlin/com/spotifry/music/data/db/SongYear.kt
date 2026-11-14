package com.spotifry.music.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Release year of a song, looked up once. [year] null means the lookup found nothing. */
@Entity(tableName = "song_years")
data class SongYearEntity(@PrimaryKey val songId: String, val year: Int?, val checkedAt: Long)

@Dao
interface SongYearDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(e: SongYearEntity)
    @Query("SELECT * FROM song_years") fun observeAll(): Flow<List<SongYearEntity>>
    @Query("SELECT * FROM song_years") suspend fun all(): List<SongYearEntity>
}
