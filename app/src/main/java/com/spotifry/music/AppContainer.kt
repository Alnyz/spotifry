package com.spotifry.music

import android.content.Context
import com.spotifry.music.data.DownloadRepository
import com.spotifry.music.data.HistoryRecorder
import com.spotifry.music.data.RecentSearches
import com.spotifry.music.data.Settings
import com.spotifry.music.data.StreamResolver
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.create
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.NewPipeSource
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.PlaybackEvents
import com.spotifry.music.player.PlayerController
import okhttp3.OkHttpClient

/** The one place the app's long-lived pieces are created. */
class AppContainer(private val context: Context) {
    val filesDir: java.io.File get() = context.filesDir
    /**
     * Drops a connection after 10 s unused: the phone's network silently cuts idle ones (measured: well under a
     * minute), and reusing a dead one costs a 10 s timeout per try, so a song took 20 s to start after a pause.
     */
    val client: OkHttpClient = OkHttpClient.Builder().connectionPool(okhttp3.ConnectionPool(5, 10, java.util.concurrent.TimeUnit.SECONDS)).build()
    /** Covers only: keeps up to 50 MB on disk, so a cover seen once loads instantly next time, even offline. */
    val imageClient: OkHttpClient = client.newBuilder().cache(okhttp3.Cache(java.io.File(context.cacheDir, "covers"), 50L * 1024 * 1024)).build()
    /** Songs played but not saved: up to the size chosen in Settings (1 GB at first), least recently played go first. Replays start at once and use no data. */
    @get:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val songCache by lazy {
        androidx.media3.datasource.cache.SimpleCache(
            java.io.File(context.cacheDir, "songs"),
            settings.cacheMb.value.let { mb ->
                if (mb == 0) androidx.media3.datasource.cache.NoOpCacheEvictor() else androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(mb * 1024L * 1024)
            },
            androidx.media3.database.StandaloneDatabaseProvider(context),
        )
    }
    val db: AppDatabase = AppDatabase.create(context)
    /** Each song's own start and end. Every queue the app or the service builds plays the trimmed part (see toMediaItem). */
    val trims = com.spotifry.music.player.Trims(context.getSharedPreferences("trims", Context.MODE_PRIVATE))
    /** Talking skipped at the start and end of music videos, when the user's own trim doesn't say otherwise. */
    val smartTrims = com.spotifry.music.player.SmartTrims(context.getSharedPreferences("smart_trims", Context.MODE_PRIVATE), client)
    val source: MusicSource = com.spotifry.music.data.source.ChoosingSource(
        NewPipeSource(), com.spotifry.music.data.source.JioSaavnSource(client)::find, { settings.jioSaavn.value },
        com.spotifry.music.data.source.MonochromeSource(client)::find, { settings.monochrome.value },
    )
    val musicBrainz = com.spotifry.music.data.spotifried.MusicBrainz(client, userAgent = "Spotifry/${BuildConfig.VERSION_NAME} (https://github.com/Alnyz/spotifry)")
    val resolver = StreamResolver(source)
    // "Play the whole song" means whole: the smart trim is turned off for that song too
    val player by lazy { PlayerController(context.applicationContext, recentlyPlayed = ::recentlyPlayed, knownKbps = ::streamKbps, saveTrim = { id, t -> trims[id] = t; if (t == null) smartTrims.turnOff(id) }) }
    /**
     * The playing song's bitrate for the quality label: YouTube's figure when it was looked up this run, otherwise
     * worked out from the saved file's or the cached song's size over its whole length ([wholeMs]).
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun streamKbps(id: String, wholeMs: Long): Int? {
        resolver.knownKbps(id)?.let { return it }
        if (wholeMs <= 0) return null
        val songs = java.io.File(context.filesDir, "songs")
        val bytes = listOf("webm", "m4a", "flac").map { java.io.File(songs, "$id.$it") }.firstOrNull { it.exists() }?.length()
            ?: androidx.media3.datasource.cache.ContentMetadata.getContentLength(songCache.getContentMetadata(id)).takeIf { it > 0 }
        return bytes?.let { (it * 8 / wholeMs).toInt() } // bits per millisecond is kbps
    }

    /** Songs listened to in the last 24 hours: Shuffle plays them in the second half. */
    suspend fun recentlyPlayed(): Set<String> = db.plays().playedSince(System.currentTimeMillis() - 24 * 60 * 60 * 1000L).toSet()
    /** One lyrics lookup for Now playing and Share, so a song's lyrics are fetched once. */
    val lyrics = com.spotifry.music.data.LyricsSource(client) { settings.lyricsOff.value }
    val historyRecorder = HistoryRecorder(db.history(), db.songs())
    val listenLog = com.spotifry.music.data.ListenLog(db.listens())
    val events = PlaybackEvents()
    val sleepTimer = com.spotifry.music.player.SleepTimer()
    /** Live picture of the sound, drawn on the Equalizer page. */
    val spectrum = com.spotifry.music.player.SpectrumTap()
    val autoEq = com.spotifry.music.data.AutoEqSource(client, java.io.File(filesDir, "autoeq"))
    val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    val settings = Settings(prefs)
        .also { s -> com.spotifry.music.player.trimOf = { com.spotifry.music.player.trimFor(trims[it], smartTrims[it], s.smartTrim.value) } }
    val updates = com.spotifry.music.data.Updates(context.applicationContext, client, prefs, BuildConfig.VERSION_NAME)
    val localMusic = com.spotifry.music.data.LocalMusic(context.applicationContext, settings.hideNonMusic)
    val downloads = DownloadRepository(context.filesDir, client, resolver, db.songs(), lowQuality = { settings.saveLow.value })
    private val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
    val restoreQueue = com.spotifry.music.data.RestoreQueue(context.getSharedPreferences("restore_pending", Context.MODE_PRIVATE), db.songs(), downloads::save, { connectivity.isActiveNetworkMetered })
    val backups = com.spotifry.music.data.Backups(db.backup(), prefs, context.getSharedPreferences("trims", Context.MODE_PRIVATE), restoreQueue, BuildConfig.VERSION_NAME)

    /** Data saver is on and the phone is on mobile data (or any connection that counts data). */
    fun streamLow(): Boolean = settings.dataSaver.value && connectivity.isActiveNetworkMetered
    /** Fetches the links of songs likely to be tapped next; not with data saver on mobile data. */
    fun warm(songs: List<Song>) { if (!streamLow()) resolver.warm(songs) }
    /** Songs marked "Not this song": Smart shuffle never suggests them again. */
    fun notSuggested(): Set<String> = prefs.getStringSet("not_suggested", null).orEmpty()
    fun setNotSuggested(id: String, no: Boolean) =
        prefs.edit().putStringSet("not_suggested", if (no) notSuggested() + id else notSuggested() - id).apply()
    val recentSearches = RecentSearches(prefs.getString("recent_searches", "").orEmpty().split('\n').filter { it.isNotEmpty() }) {
        prefs.edit().putString("recent_searches", it.joinToString("\n")).apply()
    }
}
