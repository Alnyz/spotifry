package com.spotifry.music.data

import com.spotifry.music.data.db.BackupDao
import com.spotifry.music.data.db.HistoryEntity
import com.spotifry.music.data.db.ListenEntity
import com.spotifry.music.data.db.PlayCountEntity
import com.spotifry.music.data.db.SongDao
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.db.SongNoteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.InputStream

/** Settings only the desktop app has (folders, window) start with this, and stay out of every backup. */
const val DESKTOP_KEY_PREFIX = "desktop_"

/** Keys in the `app` prefs that describe this phone or this install, not the listener's choices. */
private fun aboutThisPhone(key: String) = key.startsWith(DESKTOP_KEY_PREFIX) || key == FIRST_LAUNCH_SEEN || key == UPDATE_LAST_CHECK || key == UPDATE_LATER || key.startsWith(PlaylistOrders.KEY_PREFIX) || key == Settings.FOR_YOU || key == Settings.FANART_KEY

/**
 * What the app reads each setting as. Reading a pref as another type throws, and Settings reads them all as the app starts,
 * so one wrong value would stop the app opening until its data was cleared. A key added later and missing here is only
 * checked against this phone's value; add it here.
 */
val SETTING_TYPES: Map<String, String> = plainSettingTypes().let { plain ->
    // each sound set keeps its own copy of the sound settings, read back as the same types
    plain + Settings.SET_PREFIX.values.flatMap { p -> Settings.SOUND_KEYS.mapNotNull { k -> plain[k]?.let { p + k to it } } }
}

private fun plainSettingTypes(): Map<String, String> =
    listOf(Settings.JIOSAAVN, Settings.MONOCHROME, Settings.DATA_SAVER, Settings.SAVE_LOW, Settings.KEEP_SCREEN_ON, Settings.SAME_LOUDNESS, Settings.SKIP_SILENCE, Settings.SMART_TRIM, Settings.EQ_ON, Settings.MONO, Settings.SPATIAL_ON, Settings.PURE_BLACK, Settings.SOUND_PER_DEVICE, Settings.TUNE_LIMITER).associateWith { "boolean" } +
        listOf(Settings.PITCH, Settings.FADE, Settings.TRANSITION, Settings.SILENCE_DB, Settings.SILENCE_CUT, Settings.ACCENT_COLOR, Settings.SPATIAL_HZ, Settings.SPATIAL_DB, Settings.SPATIAL_AMOUNT,
            Settings.TUNE_BALANCE, Settings.TUNE_WIDTH, Settings.TUNE_GAIN, Settings.TUNE_HIGH_PASS, Settings.TUNE_LOW_PASS, Settings.TUNE_NIGHT).associateWith { "int" } +
        (Settings.SPEED to "float") +
        listOf(Settings.ARTISTS, Settings.NAME, Settings.THEME, Settings.EQ_PRESET, Settings.EQ_LEVELS, Settings.EQ_BANDS_KEY, Settings.EQ_BEFORE, Settings.EQ_SHAPE, Settings.EQ_BASE, Settings.SOUND_SET, Settings.ACCENT, Settings.ARTIST_NAMES, "recent_searches").associateWith { "string" } +
        ("not_suggested" to "set")

/** Makes a [BackupFile] from this phone, and merges one into it. */
class Backups(
    private val dao: BackupDao,
    private val app: Prefs,
    private val trims: Prefs,
    private val waiting: RestoreQueue,
    private val appVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
    /** Where the playlist cover pictures are kept; null where there are none (desktop): covers are then neither saved nor restored. */
    private val covers: File? = null,
    /** Where playlist and favourite-artist name logos are kept; null (desktop): logos are neither saved nor restored. */
    private val playlistLogos: File? = null,
    private val artistLogos: File? = null,
) {
    suspend fun build(): BackupFile {
        fun here(id: String) = !id.startsWith(LOCAL_PREFIX)
        val rows = dao.playlistSongs().groupBy { it.playlistId }
        return BackupFile(
            appVersion, now(),
            dao.songs().filter { here(it.id) }.map { it.copy(savedAt = if (it.filePath != null) it.savedAt ?: 0 else null, filePath = null, thumbPath = null) },
            dao.playlists().map { p ->
                BackupPlaylist(
                    p.name, p.createdAt, app.getString(PlaylistOrders.key(p.id), null), rows[p.id].orEmpty().filter { here(it.songId) }.map { BackupPlaylistSong(it.songId, it.addedAt) },
                    coverFile(p.cover)?.let { f -> runCatching { f.readBytes().toByteString() }.getOrNull()?.takeIf(::isJpeg) },
                    p.nameStyle, p.nameShow, p.logoTint, pngIn(playlistLogos, p.nameLogo),
                )
            },
            dao.history().filter { here(it.songId) }, dao.plays().filter { here(it.songId) }, dao.listens(),
            app.all.filterKeys { !aboutThisPhone(it) }.mapNotNull { (k, v) -> v?.let { k to it } }.toMap(),
            trims.all.filterKeys(::here).mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap(),
            dao.notes().filter { here(it.songId) },
            decodeArtistNames(app.getString(Settings.ARTIST_NAMES, null)).values.mapNotNull { it.logoFile }.distinct()
                .mapNotNull { n -> pngIn(artistLogos, n)?.let { n to it } }.toMap(),
        )
    }

    /** The backup's settings that this app couldn't read: they are left out, and this phone's value stays. */
    fun unusableSettings(b: BackupFile): Set<String> {
        val here = app.all
        return b.settings.filter { (k, v) -> (SETTING_TYPES[k] ?: here[k]?.let(::prefType))?.let { it != prefType(v) } == true }.keys
    }

    /**
     * The database merges in one transaction, first, so a failure there leaves everything as it was; a failure after it is
     * a [BackupPartlyApplied]. Restart the app after a success, and not after a failure: the running app shows the merged
     * library by itself and goes on with its old settings. Returns how many settings were left out.
     */
    suspend fun restore(b: BackupFile): Int {
        val ids = dao.merge(b)
        try { applyTheRest(b, ids) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { throw BackupPartlyApplied(e) }
        return unusableSettings(b).size
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun applyTheRest(b: BackupFile, ids: List<Pair<Long, Boolean>>) {
        val saved = b.songs.filter { it.savedAt != null }.sortedBy { it.savedAt }.map { it.id }
        // oldest first: each download is stamped with its own time, so the Saved page comes back in its old order
        waiting.add(saved - dao.savedIds().toSet())
        check(trims.edit().apply { b.trims.forEach { (k, v) -> if (!trims.contains(k)) putString(k, v) } }.commit()) { "trims not written" }
        val unusable = unusableSettings(b)
        check(app.edit().apply {
            // a backup without a name leaves this phone's name alone
            app.all.keys.filterNot { aboutThisPhone(it) || it in unusable || (it == Settings.NAME && Settings.NAME !in b.settings) }.forEach { remove(it) }
            b.settings.filterKeys { !aboutThisPhone(it) && it !in unusable }.forEach { (k, v) ->
                when (v) {
                    is Boolean -> putBoolean(k, v); is Int -> putInt(k, v); is Long -> putLong(k, v); is Float -> putFloat(k, v)
                    is String -> putString(k, if (k == Settings.NAME) cleanName(v) else v); is Set<*> -> putStringSet(k, v as Set<String>)
                }
            }
            // a playlist already on this phone keeps the order picked here
            b.playlists.zip(ids).forEach { (p, id) -> if (id.second) p.order?.let { putString(PlaylistOrders.key(id.first), it) } }
            putBoolean(FIRST_LAUNCH_SEEN, true) // a phone with a restored library has no use for the first-launch screens
        }.commit()) { "settings not written" }
        b.playlists.zip(ids).forEach { (p, id) -> p.cover?.let { restoreCover(id.first, it) } }
        b.playlists.zip(ids).forEach { (p, id) -> p.nameLogo?.let { restoreNameLogo(id.first, it) } }
        artistLogos?.let { dir -> b.artistLogos.forEach { (n, png) -> if (isPlainFileName(n)) writeIfMissing(File(dir, n), png) } }
    }

    private fun pngIn(dir: File?, name: String?): okio.ByteString? =
        dir?.let { d -> name?.takeIf(::isPlainFileName)?.let { File(d, it) } }?.takeIf { it.isFile }?.let { f -> runCatching { f.readBytes().toByteString() }.getOrNull() }?.takeIf(::isPng)

    /** Artist logo names carry a time and a random part, so a file already here under that name is the same logo. */
    private fun writeIfMissing(f: File, png: okio.ByteString) {
        if (f.exists()) return
        try { f.parentFile.mkdirs(); f.writeBytes(png.toByteArray()) } catch (e: java.io.IOException) { f.delete() }
    }

    /** A playlist that already has a name logo here keeps it. */
    private suspend fun restoreNameLogo(id: Long, png: okio.ByteString) {
        val dir = playlistLogos ?: return
        val f = File(dir, "$id-${now()}.png")
        try {
            dir.mkdirs()
            f.writeBytes(png.toByteArray())
            if (dao.setNameLogoIfNone(id, f.name) == 0) f.delete()
        } catch (e: java.io.IOException) { f.delete() }
    }

    private fun coverFile(name: String?): File? = covers?.let { dir -> name?.let { File(dir, it) } }?.takeIf { it.isFile }

    /** A playlist that already has a cover on this phone keeps it. A cover that can't be written leaves the default. */
    private suspend fun restoreCover(id: Long, jpeg: ByteString) {
        val dir = covers ?: return
        if (dao.coverOf(id) != null) return
        val f = File(dir, "$id-${now()}.jpg") // a fresh name: the old phone's name may already be taken here
        try {
            dir.mkdirs()
            f.writeBytes(jpeg.toByteArray())
            if (dao.setCoverIfNone(id, f.name) == 0) f.delete()
        } catch (e: java.io.IOException) { f.delete() }
    }
}

sealed interface RestoreStatus {
    data object Idle : RestoreStatus
    data object Running : RestoreStatus
    data object NeedsWifi : RestoreStatus
    data class Failed(val count: Int) : RestoreStatus
}

/**
 * Songs that were saved on the old phone and have no file here, oldest first, kept in a pref until downloaded or forgotten.
 * ponytail: a plain pref, one id per line, and the queue only runs while the app's process lives; pressing Download again
 * continues. A table and a foreground service if a long restore keeps getting killed.
 */
class RestoreQueue(
    private val prefs: Prefs,
    private val songs: SongDao,
    private val save: suspend (com.spotifry.music.data.source.Song) -> Unit,
    private val metered: () -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _ids = MutableStateFlow(prefs.getString(KEY, "").orEmpty().lines().filter { it.isNotEmpty() })
    val ids: StateFlow<List<String>> = _ids
    private val _status = MutableStateFlow<RestoreStatus>(RestoreStatus.Idle)
    val status: StateFlow<RestoreStatus> = _status
    private var job: Job? = null

    private fun store(list: List<String>) {
        _ids.value = list
        prefs.edit().putString(KEY, list.joinToString("\n")).apply()
    }

    /** Written at once: the app exits right after a restore. */
    fun add(new: List<String>) {
        _ids.value = (_ids.value + new).distinct()
        check(prefs.edit().putString(KEY, _ids.value.joinToString("\n")).commit()) { "list not written" }
    }

    fun forget() { stop(); store(emptyList()) }

    fun start(wifiOnly: Boolean) {
        if (job?.isActive == true) return
        _status.value = RestoreStatus.Running
        job = scope.launch { run(wifiOnly) }
    }

    /** The song downloading now still finishes, like one the listener saved by hand. */
    fun stop() { job?.cancel(); _status.value = RestoreStatus.Idle }

    suspend fun run(wifiOnly: Boolean) {
        var failed = 0
        var inARow = 0
        for (id in _ids.value) {
            if (wifiOnly && metered()) { _status.value = RestoreStatus.NeedsWifi; return }
            val row = songs.get(id)
            if (row == null || row.isSaved) { store(_ids.value - id); continue }
            save(row.toSong())
            if (songs.get(id)?.isSaved == true) { store(_ids.value - id); inARow = 0; continue }
            failed++
            // the connection is gone, not the songs: stop before every song in the list reports a failure
            if (++inARow == MAX_FAILS_IN_A_ROW) break
        }
        _status.value = if (failed > 0) RestoreStatus.Failed(failed) else RestoreStatus.Idle
    }

    private companion object {
        const val KEY = "restore_pending"
        const val MAX_FAILS_IN_A_ROW = 3
    }
}

