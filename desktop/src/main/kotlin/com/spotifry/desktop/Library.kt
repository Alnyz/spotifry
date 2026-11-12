package com.spotifry.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.spotifry.music.data.Backups
import com.spotifry.music.data.DownloadRepository
import com.spotifry.music.data.HistoryRecorder
import com.spotifry.music.data.ListenLog
import com.spotifry.music.data.PlaylistOrders
import com.spotifry.music.data.Prefs
import com.spotifry.music.data.RestoreQueue
import com.spotifry.music.data.Settings
import com.spotifry.music.data.StreamResolver
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.AppDatabase_Impl
import com.spotifry.music.player.Trims
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import java.io.File

const val APP_VERSION = "desktop-0.1.0"
/** The same version as gradle/libs.versions.toml, for the About line. */
const val NEWPIPE_VERSION = "v0.26.5"

/** Everything the desktop app keeps: the database, the settings, the saved songs and the backup tools. */
class Library(
    val db: AppDatabase, val prefs: Prefs, trimPrefs: Prefs, restorePrefs: Prefs, dir: File, val client: OkHttpClient, resolver: StreamResolver,
) {
    val settings = Settings(prefs)
    /** Where saved songs are kept: a folder named songs inside the app's data folder. */
    val saveFolder = File(dir, "songs")
    val orders = PlaylistOrders(prefs)
    val trims = Trims(trimPrefs)
    val smartTrims = com.spotifry.music.player.SmartTrims(FilePrefs(File(dir, "smart_trims.json")), client)
    val downloads = DownloadRepository(dir, client, resolver, db.songs(), lowQuality = { settings.saveLow.value })
    val lyrics = com.spotifry.music.data.LyricsSource(client) { settings.lyricsOff.value }
    val history = HistoryRecorder(db.history(), db.songs())
    val listenLog = ListenLog(db.listens())
    val restoreQueue = RestoreQueue(restorePrefs, db.songs(), downloads::save, metered = { false })
    val backups = Backups(db.backup(), prefs, trimPrefs, restoreQueue, APP_VERSION)

    val recorder = PlayRecorder(history, listenLog) { id -> db.plays().count(id, System.currentTimeMillis()) }

    /** Songs marked "Not this song": Smart shuffle never suggests them again. The phone's key, so a backup carries it. */
    fun notSuggested(): Set<String> = prefs.getStringSet("not_suggested", null).orEmpty()
    fun setNotSuggested(id: String, no: Boolean) =
        prefs.edit().putStringSet("not_suggested", if (no) notSuggested() + id else notSuggested() - id).apply()

    /** The file of a song saved on this computer, if it is still there. */
    suspend fun localFile(song: com.spotifry.music.data.source.Song): String? =
        db.songs().get(song.id)?.filePath?.takeIf { File(it).isFile }

    companion object {
        /** The real library in [dir]: a database file and a few settings files. */
        fun open(dir: File, client: OkHttpClient, resolver: StreamResolver): Library {
            val db = Room.databaseBuilder<AppDatabase>(File(dir, "music.db").path, factory = { AppDatabase_Impl() })
                .setDriver(BundledSQLiteDriver()).addMigrations(*AppDatabase.driverMigrations()).setQueryCoroutineContext(Dispatchers.IO).build()
            return Library(db, FilePrefs(File(dir, "settings.json")), FilePrefs(File(dir, "trims.json")), FilePrefs(File(dir, "restore_pending.json")), dir, client, resolver)
        }
    }
}
