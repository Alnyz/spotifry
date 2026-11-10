package com.spotifry.music.data.spotifried

import com.spotifry.music.data.db.ListenRow
import com.spotifry.music.data.db.SongEntity
import java.time.LocalDateTime
import java.time.ZoneOffset

val testZone: ZoneOffset = ZoneOffset.UTC
fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).toInstant(testZone).toEpochMilli()
fun song(id: String, artist: String = "Artist $id") = SongEntity(id, "Song $id", artist, 200, null, null, null, null)
fun row(whenMs: Long, id: String, seconds: Int, artist: String = "Artist $id") = ListenRow(whenMs, id, seconds, false, song(id, artist))
