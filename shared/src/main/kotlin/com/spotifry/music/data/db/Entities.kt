package com.spotifry.music.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.spotifry.music.data.source.Song

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val durationSec: Long,
    val thumbnailUrl: String?,
    val savedAt: Long?,
    val filePath: String?,
    val thumbPath: String?,
) {
    val isSaved get() = filePath != null
    fun toSong() = Song(id, title, artist, durationSec, thumbnailUrl, thumbPath)
}

fun Song.toEntity() = SongEntity(id, title, artist, durationSec, thumbnailUrl, null, null, null)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE)],
)
data class PlaylistSongEntity(
    val playlistId: Long, val songId: String, val position: Int,
    /** When it was added, for "Recently added"; [position] can change by hand, this never does. */
    @androidx.room.ColumnInfo(defaultValue = "0") val addedAt: Long = 0,
)

@Entity(tableName = "history")
data class HistoryEntity(@PrimaryKey val songId: String, val playedAt: Long)

/** How many times each song was listened to for at least 30 seconds. */
@Entity(tableName = "plays")
data class PlayCountEntity(@PrimaryKey val songId: String, val count: Int, val lastPlayedAt: Long)

/**
 * One listen: a song from when its sound started until the player moved on. [secondsHeard] is real time with sound, so
 * paused time isn't in it and a crossfade's overlap is in one listen only. The song's name and cover come from `songs`,
 * whose rows are never deleted. [skipped]: the listener moved on before [LISTEN_MS], see ListenTracker.
 */
@Entity(tableName = "listens")
data class ListenEntity(
    @PrimaryKey val startedAt: Long,
    val songId: String,
    val secondsHeard: Int,
    @androidx.room.ColumnInfo(defaultValue = "0") val skipped: Boolean = false,
)

/** A log line with its song; null if the song's row was never written. Its seconds still count in the totals. */
data class ListenRow(
    val startedAt: Long, val songId: String, val secondsHeard: Int, val skipped: Boolean,
    @Embedded val song: SongEntity?,
)

data class PlayCountRow(val songId: String, val n: Int)

data class PlaylistWithCounts(val id: Long, val name: String, val songCount: Int, val savedCount: Int, val number: Int)

data class PlaylistCoverRow(val playlistId: Long, @Embedded val song: SongEntity)
