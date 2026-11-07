package com.spotifry.music.data

import android.content.SharedPreferences
import com.spotifry.music.data.db.BackupDao
import com.spotifry.music.data.db.HistoryEntity
import com.spotifry.music.data.db.ListenEntity
import com.spotifry.music.data.db.PlayCountEntity
import com.spotifry.music.data.db.SongDao
import com.spotifry.music.data.db.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The newest file layout this app reads. Raise it only when an older app would misread a new file. */
const val BACKUP_FORMAT = 1
private const val ENTRY = "backup.json"
/** Five years at 30 songs a day and 5,000 songs is about 5 MB; even 100 a day for ten years fits. */
internal const val MAX_JSON_BYTES = 32 * 1024 * 1024
private const val LOCAL_PREFIX = "local:"

/** A file that can't be restored; the message is shown as it is. */
class BackupError(message: String) : Exception(message)

/** The library is merged and stays merged, but settings, trims or the songs to download again may not have been written. */
class BackupPartlyApplied(cause: Exception) : Exception(cause)

data class BackupPlaylistSong(val songId: String, val addedAt: Long)
/** [order] is the playlist's listing order. It has its own place here because its pref key holds this phone's playlist id. */
data class BackupPlaylist(val name: String, val createdAt: Long, val order: String?, val songs: List<BackupPlaylistSong>)

/**
 * Everything a backup holds. [songs] never carry a file path; a song's savedAt says it was saved on the old phone.
 * Song files on the phone ("local:" ids) are left out, except their listens: the id is only a number in this phone's
 * music list, so on another phone it would be a different song or none.
 */
data class BackupFile(
    val appVersion: String, val createdAt: Long,
    val songs: List<SongEntity>, val playlists: List<BackupPlaylist>,
    val history: List<HistoryEntity>, val plays: List<PlayCountEntity>, val listens: List<ListenEntity>,
    /** Values are Boolean, Int, Long, Float, String or Set of String: what SharedPreferences holds. */
    val settings: Map<String, Any>, val trims: Map<String, String>,
)

/** "12 playlists, 340 songs, 5 trims" */
fun BackupFile.summary() = listOf(playlists.size to "playlist", songs.size to "song", trims.size to "trim")
    .joinToString(", ") { (n, word) -> "$n $word${if (n == 1) "" else "s"}" }

fun backupFileName(today: java.time.LocalDate = java.time.LocalDate.now()) = "spotifry-backup-$today.zip"

/** Keys in the `app` prefs that describe this phone or this install, not the listener's choices. */
private fun aboutThisPhone(key: String) = key == FIRST_LAUNCH_SEEN || key == Updates.LAST_CHECK || key == Updates.LATER || key.startsWith(PlaylistOrders.KEY_PREFIX)

/**
 * What the app reads each setting as. Reading a pref as another type throws, and Settings reads them all as the app starts,
 * so one wrong value would stop the app opening until its data was cleared. A key added later and missing here is only
 * checked against this phone's value; add it here.
 */
internal val SETTING_TYPES: Map<String, String> =
    listOf(Settings.JIOSAAVN, Settings.DATA_SAVER, Settings.SAVE_LOW, Settings.KEEP_SCREEN_ON, Settings.SAME_LOUDNESS, Settings.SKIP_SILENCE, Settings.SMART_TRIM, Settings.EQ_ON, Settings.PURE_BLACK).associateWith { "boolean" } +
        listOf(Settings.PITCH, Settings.FADE, Settings.ACCENT_COLOR).associateWith { "int" } +
        (Settings.SPEED to "float") +
        listOf(Settings.ARTISTS, Settings.THEME, Settings.EQ_PRESET, Settings.EQ_LEVELS, Settings.EQ_BANDS_KEY, Settings.EQ_BEFORE, Settings.ACCENT, "recent_searches").associateWith { "string" } +
        ("not_suggested" to "set")

internal fun prefType(v: Any): String = when (v) {
    is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"; is String -> "string"; is Set<*> -> "set"
    else -> error("a pref can't be a ${v::class}")
}

fun BackupFile.encode(): String = JSONObject()
    .put("format", BACKUP_FORMAT).put("appVersion", appVersion).put("createdAt", createdAt)
    .put("songs", JSONArray(songs.map {
        JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("durationSec", it.durationSec)
            .put("thumbnailUrl", it.thumbnailUrl ?: JSONObject.NULL).put("savedAt", it.savedAt ?: JSONObject.NULL)
    }))
    .put("playlists", JSONArray(playlists.map { p ->
        JSONObject().put("name", p.name).put("createdAt", p.createdAt).put("order", p.order ?: JSONObject.NULL)
            .put("songs", JSONArray(p.songs.map { JSONObject().put("songId", it.songId).put("addedAt", it.addedAt) }))
    }))
    .put("history", JSONArray(history.map { JSONObject().put("songId", it.songId).put("playedAt", it.playedAt) }))
    .put("plays", JSONArray(plays.map { JSONObject().put("songId", it.songId).put("count", it.count).put("lastPlayedAt", it.lastPlayedAt) }))
    .put("listens", JSONArray(listens.map {
        JSONObject().put("startedAt", it.startedAt).put("songId", it.songId).put("secondsHeard", it.secondsHeard).put("skipped", it.skipped)
    }))
    .put("settings", JSONObject().apply { settings.forEach { (k, v) -> put(k, encodeValue(v)) } })
    .put("trims", JSONObject(trims as Map<*, *>))
    .toString()

// the type is written out: a JSON number doesn't say whether a pref was an Int, a Long or a Float, and reading a pref as the wrong one throws
private fun encodeValue(v: Any): JSONObject = JSONObject().put("type", prefType(v)).put("value", when (v) {
    is Float -> v.toDouble()
    is Set<*> -> JSONArray(v)
    else -> v
})

private fun decodeValue(o: JSONObject): Any? = when (o.optString("type")) {
    "boolean" -> o.getBoolean("value")
    "int" -> o.getInt("value")
    "long" -> o.getLong("value")
    "float" -> o.getDouble("value").toFloat()
    "string" -> o.getString("value")
    "set" -> o.getJSONArray("value").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    else -> null // a type from a newer app
}

private fun JSONObject.optStringOrNull(k: String) = if (isNull(k)) null else optString(k)
private fun <T> JSONObject.list(k: String, item: (JSONObject) -> T): List<T> =
    optJSONArray(k)?.let { a -> (0 until a.length()).map { item(a.getJSONObject(it)) } }.orEmpty()

/** Reads the JSON. Throws on anything malformed; unknown fields are ignored and missing ones get defaults. */
fun decodeBackup(text: String): BackupFile {
    val o = JSONObject(text)
    if (!o.has("format")) throw BackupError(NOT_A_BACKUP)
    if (o.getInt("format") > BACKUP_FORMAT) throw BackupError("This backup was made by a newer Spotifry. Update the app, then try again.")
    val settings = o.optJSONObject("settings")
    val trims = o.optJSONObject("trims")
    return BackupFile(
        o.optString("appVersion"), o.optLong("createdAt"),
        o.list("songs") {
            val id = it.getString("id")
            // the id becomes a file name when the song is downloaded again
            if (id.isBlank() || '/' in id) throw BackupError(NOT_A_BACKUP)
            SongEntity(id, it.optString("title"), it.optString("artist"), it.optLong("durationSec"), it.optStringOrNull("thumbnailUrl"), if (it.isNull("savedAt")) null else it.getLong("savedAt"), null, null)
        },
        o.list("playlists") { p ->
            BackupPlaylist(p.getString("name"), p.optLong("createdAt"), p.optStringOrNull("order"), p.list("songs") { BackupPlaylistSong(it.getString("songId"), it.optLong("addedAt")) })
        },
        o.list("history") { HistoryEntity(it.getString("songId"), it.getLong("playedAt")) },
        o.list("plays") { PlayCountEntity(it.getString("songId"), it.getInt("count"), it.getLong("lastPlayedAt")) },
        o.list("listens") { ListenEntity(it.getLong("startedAt"), it.getString("songId"), it.optInt("secondsHeard"), it.optBoolean("skipped")) },
        settings?.keys()?.asSequence()?.mapNotNull { k -> decodeValue(settings.getJSONObject(k))?.let { k to it } }?.toMap().orEmpty(),
        trims?.keys()?.asSequence()?.associateWith { trims.getString(it) }.orEmpty(),
    )
}

private const val NOT_A_BACKUP = "This file is damaged or isn't a Spotifry backup. Nothing was changed."

fun writeBackup(backup: BackupFile, out: OutputStream) = ZipOutputStream(out).use { zip ->
    zip.putNextEntry(ZipEntry(ENTRY))
    zip.write(backup.encode().toByteArray())
    zip.closeEntry()
}

/** The backup in a zip file; [BackupError] with a message to show for anything else. Changes nothing. */
fun readBackup(input: InputStream): BackupFile = try {
    ZipInputStream(input).use { zip ->
        val text = generateSequence { zip.nextEntry }.firstOrNull { it.name == ENTRY }?.let { zip.readUpTo(MAX_JSON_BYTES + 1) } ?: throw BackupError(NOT_A_BACKUP)
        if (text.size > MAX_JSON_BYTES) throw BackupError(NOT_A_BACKUP)
        decodeBackup(String(text))
    }
} catch (e: BackupError) { throw e } catch (e: Exception) { throw BackupError(NOT_A_BACKUP) } catch (e: OutOfMemoryError) {
    // parsing takes several times the file's size: a file under the limit can still be too much for this phone
    throw BackupError(NOT_A_BACKUP)
}

/** Makes a [BackupFile] from this phone, and merges one into it. */
class Backups(
    private val dao: BackupDao,
    private val app: SharedPreferences,
    private val trims: SharedPreferences,
    private val waiting: RestoreQueue,
    private val appVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun build(): BackupFile {
        fun here(id: String) = !id.startsWith(LOCAL_PREFIX)
        val rows = dao.playlistSongs().groupBy { it.playlistId }
        return BackupFile(
            appVersion, now(),
            dao.songs().filter { here(it.id) }.map { it.copy(savedAt = if (it.filePath != null) it.savedAt ?: 0 else null, filePath = null, thumbPath = null) },
            dao.playlists().map { p ->
                BackupPlaylist(p.name, p.createdAt, app.getString(PlaylistOrders.key(p.id), null), rows[p.id].orEmpty().filter { here(it.songId) }.map { BackupPlaylistSong(it.songId, it.addedAt) })
            },
            dao.history().filter { here(it.songId) }, dao.plays().filter { here(it.songId) }, dao.listens(),
            app.all.filterKeys { !aboutThisPhone(it) }.mapNotNull { (k, v) -> v?.let { k to it } }.toMap(),
            trims.all.filterKeys(::here).mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap(),
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
            app.all.keys.filterNot { aboutThisPhone(it) || it in unusable }.forEach { remove(it) }
            b.settings.filterKeys { !aboutThisPhone(it) && it !in unusable }.forEach { (k, v) ->
                when (v) {
                    is Boolean -> putBoolean(k, v); is Int -> putInt(k, v); is Long -> putLong(k, v); is Float -> putFloat(k, v)
                    is String -> putString(k, v); is Set<*> -> putStringSet(k, v as Set<String>)
                }
            }
            // a playlist already on this phone keeps the order picked here
            b.playlists.zip(ids).forEach { (p, id) -> if (id.second) p.order?.let { putString(PlaylistOrders.key(id.first), it) } }
            putBoolean(FIRST_LAUNCH_SEEN, true) // a phone with a restored library has no use for the first-launch screens
        }.commit()) { "settings not written" }
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
    private val prefs: SharedPreferences,
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

    internal suspend fun run(wifiOnly: Boolean) {
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

// InputStream.readNBytes needs Android 13
private fun InputStream.readUpTo(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < max) { val n = read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) }
    return out.toByteArray()
}
