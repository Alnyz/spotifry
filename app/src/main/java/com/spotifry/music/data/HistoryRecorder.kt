package com.spotifry.music.data

import com.spotifry.music.data.db.HistoryDao
import com.spotifry.music.data.db.SongDao
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.Song

/** Records every song that starts playing, wherever it was started from or reached. */
class HistoryRecorder(
    private val history: HistoryDao,
    private val songs: SongDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun onPlayed(song: Song) {
        songs.upsert(song.toEntity())
        history.record(song.id, now())
    }
}
