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
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

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
