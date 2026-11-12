package com.spotifry.music.data.spotifried

import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.db.SongYearDao
import com.spotifry.music.data.db.SongYearEntity

private const val RETRY_AFTER_MS = 21 * 24 * 60 * 60 * 1000L

/** Played songs without a year, most played first. A song that found nothing is asked again after three weeks. */
fun nextToLookUp(plays: Map<String, Int>, known: List<SongYearEntity>, now: Long, limit: Int = 50): List<String> {
    val done = known.filter { it.year != null || now - it.checkedAt <= RETRY_AFTER_MS }.map { it.songId }.toSet()
    return plays.filter { it.value > 0 && it.key !in done }.entries.sortedByDescending { it.value }.take(limit).map { it.key }
}

/** One request per song, [pause] after each (MusicBrainz allows about one a second). False when it asked to wait: nothing is stored then. */
suspend fun lookUpYears(songs: List<SongEntity>, mb: MusicBrainz, dao: SongYearDao, now: () -> Long, pause: suspend () -> Unit): Boolean {
    for (s in songs) {
        when (val r = mb.yearOf(s.title, s.artist)) {
            is YearResult.Found -> dao.put(SongYearEntity(s.id, r.year.takeIf { it >= 1900 }, now()))
            YearResult.NotFound -> dao.put(SongYearEntity(s.id, null, now()))
            YearResult.TryLater -> return false
        }
        pause()
    }
    return true
}
