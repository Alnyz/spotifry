package com.spotifry.music

import android.content.Context
import com.spotifry.music.data.DownloadRepository
import com.spotifry.music.data.HistoryRecorder
import com.spotifry.music.data.RecentSearches
import com.spotifry.music.data.Settings
import com.spotifry.music.data.StreamResolver
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.NewPipeSource
import com.spotifry.music.player.PlaybackEvents
import com.spotifry.music.player.PlayerController
import okhttp3.OkHttpClient

/** The one place the app's long-lived pieces are created. */
class AppContainer(private val context: Context) {
    val filesDir: java.io.File get() = context.filesDir
    val client = OkHttpClient()
    /** Covers only: keeps up to 50 MB on disk, so a cover seen once loads instantly next time, even offline. */
    val imageClient: OkHttpClient = client.newBuilder().cache(okhttp3.Cache(java.io.File(context.cacheDir, "covers"), 50L * 1024 * 1024)).build()
    /** Songs played but not saved: up to 500 MB, least recently played go first. Replays start at once and use no data. */
    @get:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val songCache by lazy {
        androidx.media3.datasource.cache.SimpleCache(
            java.io.File(context.cacheDir, "songs"),
            androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(500L * 1024 * 1024),
            androidx.media3.database.StandaloneDatabaseProvider(context),
        )
    }
    val db: AppDatabase = AppDatabase.create(context)
    val source: MusicSource = NewPipeSource()
    val resolver = StreamResolver(source)
    val player by lazy { PlayerController(context.applicationContext) }
    val historyRecorder = HistoryRecorder(db.history(), db.songs())
    val events = PlaybackEvents()
    val sleepTimer = com.spotifry.music.player.SleepTimer()
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    val settings = Settings(prefs)
    val downloads = DownloadRepository(context.filesDir, client, resolver, db.songs(), lowQuality = { settings.saveLow.value })
    private val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)

    /** Data saver is on and the phone is on mobile data (or any connection that counts data). */
    fun streamLow(): Boolean = settings.dataSaver.value && connectivity.isActiveNetworkMetered
    val recentSearches = RecentSearches(prefs.getString("recent_searches", "").orEmpty().split('\n').filter { it.isNotEmpty() }) {
        prefs.edit().putString("recent_searches", it.joinToString("\n")).apply()
    }
}
