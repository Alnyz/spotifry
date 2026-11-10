package com.spotifry.music.data.spotifried

import com.spotifry.music.data.db.ListenRow
import com.spotifry.music.data.db.SongEntity
import java.time.LocalDateTime
import java.time.ZoneOffset

val testZone: ZoneOffset = ZoneOffset.UTC
fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).toInstant(testZone).toEpochMilli()
fun song(id: String, artist: String = "Artist $id") = SongEntity(id, "Song $id", artist, 200, null, null, null, null)
fun row(whenMs: Long, id: String, seconds: Int, artist: String = "Artist $id") = ListenRow(whenMs, id, seconds, false, song(id, artist))

/** Neutral stats for rule tests: set only the fields a test cares about. */
fun stats(
    songs: Int = 0, plays: Int = 0, seconds: Long = 0, daySeconds: List<Long> = listOf(0),
    hours: List<Long> = List(24) { 0L }, longestStreak: Int = 0, newSongs: Int = 0, replayedSongs: Int = 0,
    topArtist: ArtistShare? = null, earlyShare: Float = 0f, nightShare: Float = 0f, age: AgeStats = ageStats(),
    period: WrappedPeriod = calendarYear(2026, java.time.LocalDate.of(2026, 12, 31)),
) = SpotifriedStats(
    period, period.from, plays, seconds, songs, null, daySeconds, null, hours, null, emptyList(), null,
    longestStreak, newSongs, replayedSongs, topArtist, earlyShare, nightShare, age,
)

fun ageStats(typicalYear: Int? = null, oldestYear: Int? = null, songsWithYear: Int = 0, songsTotal: Int = 0) =
    AgeStats(songsWithYear, songsTotal, 0, emptyList(), typicalYear, typicalYear?.let { 2026 - it + 14 }, oldestYear, null)
