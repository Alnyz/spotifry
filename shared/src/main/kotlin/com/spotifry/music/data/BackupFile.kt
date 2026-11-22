package com.spotifry.music.data

import com.spotifry.music.data.db.HistoryEntity
import com.spotifry.music.data.db.ListenEntity
import com.spotifry.music.data.db.PlayCountEntity
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.db.SongNoteEntity
import org.json.JSONArray
import org.json.JSONObject
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The newest file layout this app reads. Raise it only when an older app would misread a new file. */
const val BACKUP_FORMAT = 1
private const val ENTRY = "backup.json"
/** Five years at 30 songs a day and 5,000 songs is about 5 MB; even 100 a day for ten years fits. */
const val MAX_JSON_BYTES = 32 * 1024 * 1024
/** A cover is an 800 px JPEG of tens of KB; anything this big is not one. */
const val MAX_COVER_BYTES = 4 * 1024 * 1024
private const val COVERS = "covers/"
private const val NAME_LOGOS = "name_logos/"
private const val ARTIST_LOGOS = "artist_logos/"
/** Ids of songs that are files on this device, left out of a backup. */
const val LOCAL_PREFIX = "local:"

/** A file that can't be restored; the message is shown as it is. */
class BackupError(message: String) : Exception(message)

/** The library is merged and stays merged, but settings, trims or the songs to download again may not have been written. */
class BackupPartlyApplied(cause: Exception) : Exception(cause)

data class BackupPlaylistSong(val songId: String, val addedAt: Long)
/**
 * [order] is the playlist's listing order. It has its own place here because its pref key holds this phone's playlist id.
 * [cover] is the picture chosen as its cover (a JPEG), null for the default. It travels as its own file in the zip, which an
 * older app skips.
 */
data class BackupPlaylist(val name: String, val createdAt: Long, val order: String?, val songs: List<BackupPlaylistSong>, val cover: ByteString? = null,
    /** The name look; [nameLogo] is the logo picture (a PNG), a file of its own in the zip like [cover]. */
    val nameStyle: String? = null, val nameShow: String? = null, val logoTint: Boolean = true, val nameLogo: ByteString? = null,
)

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
    /** Absent in backups made before notes: they restore with none. */
    val notes: List<SongNoteEntity> = emptyList(),
    /** Favourite artists' own logos (PNGs) by file name, as named in the artist_names setting. */
    val artistLogos: Map<String, ByteString> = emptyMap(),
)

/** "12 playlists, 340 songs, 5 trims" */
fun BackupFile.summary() = listOf(playlists.size to "playlist", songs.size to "song", trims.size to "trim")
    .joinToString(", ") { (n, word) -> "$n $word${if (n == 1) "" else "s"}" }

fun backupFileName(today: java.time.LocalDate = java.time.LocalDate.now()) = "spotifry-backup-$today.zip"

fun prefType(v: Any): String = when (v) {
    is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"; is String -> "string"; is Set<*> -> "set"
    else -> error("a pref can't be a ${v::class}")
}

fun BackupFile.encode(): String = JSONObject()
    .put("format", BACKUP_FORMAT).put("appVersion", appVersion).put("createdAt", createdAt)
    .put("songs", JSONArray(songs.map {
        JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("durationSec", it.durationSec)
            .put("thumbnailUrl", it.thumbnailUrl ?: JSONObject.NULL).put("savedAt", it.savedAt ?: JSONObject.NULL)
    }))
    .put("playlists", JSONArray(playlists.mapIndexed { i, p ->
        JSONObject().put("name", p.name).put("createdAt", p.createdAt).put("order", p.order ?: JSONObject.NULL)
            .apply { if (p.cover != null) put("cover", coverEntry(i)) }
            .put("nameStyle", p.nameStyle ?: JSONObject.NULL).put("nameShow", p.nameShow ?: JSONObject.NULL).put("logoTint", p.logoTint)
            .apply { if (p.nameLogo != null) put("nameLogo", nameLogoEntry(i)) }
            .put("songs", JSONArray(p.songs.map { JSONObject().put("songId", it.songId).put("addedAt", it.addedAt) }))
    }))
    .put("history", JSONArray(history.map { JSONObject().put("songId", it.songId).put("playedAt", it.playedAt) }))
    .put("plays", JSONArray(plays.map { JSONObject().put("songId", it.songId).put("count", it.count).put("lastPlayedAt", it.lastPlayedAt) }))
    .put("listens", JSONArray(listens.map {
        JSONObject().put("startedAt", it.startedAt).put("songId", it.songId).put("secondsHeard", it.secondsHeard).put("skipped", it.skipped)
    }))
    .put("settings", JSONObject().apply { settings.forEach { (k, v) -> put(k, encodeValue(v)) } })
    .put("trims", JSONObject(trims as Map<*, *>))
    .put("notes", JSONArray(notes.map { JSONObject().put("songId", it.songId).put("text", it.text).put("editedAt", it.editedAt) }))
    .put("artistLogos", JSONArray(artistLogos.keys.toList()))
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

private fun coverEntry(playlist: Int) = "${COVERS}$playlist.jpg"
private fun nameLogoEntry(playlist: Int) = "${NAME_LOGOS}$playlist.png"

/** Only what starts like a PNG: logos are always kept as PNGs. */
fun isPng(b: ByteString) = b.size > 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()

/** A plain file name, so a crafted backup can't write outside the logos folder. */
fun isPlainFileName(n: String) = n.isNotBlank() && '/' !in n && '\\' !in n && n != "." && n != ".."

/** Only what starts like a JPEG: anything else would show as a blank cover, so the playlist keeps the default instead. */
fun isJpeg(b: ByteString) = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()

/**
 * Reads the JSON. Throws on anything malformed; unknown fields are ignored and missing ones get defaults.
 * [files]: the other files in the zip by name, where the covers are; a missing or broken cover leaves the default.
 */
fun decodeBackup(text: String, files: Map<String, ByteString> = emptyMap()): BackupFile {
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
            BackupPlaylist(
                p.getString("name"), p.optLong("createdAt"), p.optStringOrNull("order"), p.list("songs") { BackupPlaylistSong(it.getString("songId"), it.optLong("addedAt")) },
                p.optStringOrNull("cover")?.let(files::get)?.takeIf(::isJpeg),
                p.optStringOrNull("nameStyle"), p.optStringOrNull("nameShow"), p.optBoolean("logoTint", true),
                p.optStringOrNull("nameLogo")?.let(files::get)?.takeIf(::isPng),
            )
        },
        o.list("history") { HistoryEntity(it.getString("songId"), it.getLong("playedAt")) },
        o.list("plays") { PlayCountEntity(it.getString("songId"), it.getInt("count"), it.getLong("lastPlayedAt")) },
        o.list("listens") { ListenEntity(it.getLong("startedAt"), it.getString("songId"), it.optInt("secondsHeard"), it.optBoolean("skipped")) },
        settings?.keys()?.asSequence()?.mapNotNull { k -> decodeValue(settings.getJSONObject(k))?.let { k to it } }?.toMap().orEmpty(),
        trims?.keys()?.asSequence()?.associateWith { trims.getString(it) }.orEmpty(),
        // cleaned again: the file may have been edited by hand
        o.list("notes") { SongNoteEntity(it.getString("songId"), cleanNote(it.optString("text")), it.optLong("editedAt")) }.filter { it.text.isNotEmpty() },
        o.optJSONArray("artistLogos")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            .filter(::isPlainFileName).mapNotNull { n -> files[ARTIST_LOGOS + n]?.takeIf(::isPng)?.let { n to it } }.toMap(),
    )
}

private const val NOT_A_BACKUP = "This file is damaged or isn't a Spotifry backup. Nothing was changed."

fun writeBackup(backup: BackupFile, out: OutputStream) = ZipOutputStream(out).use { zip ->
    zip.putNextEntry(ZipEntry(ENTRY))
    zip.write(backup.encode().toByteArray())
    zip.closeEntry()
    backup.playlists.forEachIndexed { i, p ->
        p.cover?.let { zip.putNextEntry(ZipEntry(coverEntry(i))); zip.write(it.toByteArray()); zip.closeEntry() }
        p.nameLogo?.let { zip.putNextEntry(ZipEntry(nameLogoEntry(i))); zip.write(it.toByteArray()); zip.closeEntry() }
    }
    backup.artistLogos.forEach { (n, b) -> zip.putNextEntry(ZipEntry(ARTIST_LOGOS + n)); zip.write(b.toByteArray()); zip.closeEntry() }
}

/** The backup in a zip file; [BackupError] with a message to show for anything else. Changes nothing. */
fun readBackup(input: InputStream): BackupFile = try {
    ZipInputStream(input).use { zip ->
        var text: ByteArray? = null
        val covers = mutableMapOf<String, ByteString>()
        var coverBytes = 0L
        for (e in generateSequence { zip.nextEntry }) when {
            e.name == ENTRY && text == null -> text = zip.readUpTo(MAX_JSON_BYTES + 1)
            // ponytail: one cover per playlist and a cap on the total, so a crafted zip can't fill the memory
            (e.name.startsWith(COVERS) || e.name.startsWith(NAME_LOGOS) || e.name.startsWith(ARTIST_LOGOS)) && coverBytes < MAX_JSON_BYTES -> zip.readUpTo(MAX_COVER_BYTES + 1)
                .takeIf { it.size <= MAX_COVER_BYTES }?.let { covers[e.name] = it.toByteString(); coverBytes += it.size }
        }
        if (text == null || text.size > MAX_JSON_BYTES) throw BackupError(NOT_A_BACKUP)
        decodeBackup(String(text), covers)
    }
} catch (e: BackupError) { throw e } catch (e: Exception) { throw BackupError(NOT_A_BACKUP) } catch (e: OutOfMemoryError) {
    // parsing takes several times the file's size: a file under the limit can still be too much for this phone
    throw BackupError(NOT_A_BACKUP)
}

// InputStream.readNBytes needs Android 13
private fun InputStream.readUpTo(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < max) { val n = read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) }
    return out.toByteArray()
}
