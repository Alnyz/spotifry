package com.spotifry.music.data.spotifried

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.spotifry.music.MusicApp
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.db.SongYearDao
import com.spotifry.music.data.db.SongYearEntity
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

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

class YearLookupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as MusicApp).container
        while (true) {
            val plays = c.db.listens().playCounts().associate { it.songId to it.n }
            val ids = nextToLookUp(plays, c.db.songYears().all(), System.currentTimeMillis())
            if (ids.isEmpty()) return Result.success()
            val songs = ids.mapNotNull { c.db.songs().get(it) }
            // A played song with no row would be picked again for ever, so give up on this round instead of looping
            if (songs.isEmpty()) return Result.success()
            if (!lookUpYears(songs, c.musicBrainz, c.db.songYears(), System::currentTimeMillis) { delay(1000) }) return Result.retry()
        }
    }
}

fun enqueueYearLookup(context: Context) {
    val request = OneTimeWorkRequestBuilder<YearLookupWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork("song-years", ExistingWorkPolicy.KEEP, request)
}
