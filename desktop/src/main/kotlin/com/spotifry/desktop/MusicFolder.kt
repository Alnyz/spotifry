package com.spotifry.desktop

import com.spotifry.music.data.AudioRow
import com.spotifry.music.data.LOCAL_PREFIX
import com.spotifry.music.data.Prefs
import com.spotifry.music.data.isNonMusic
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** The desktop's own setting (left out of backups): the folder whose music shows under Saved, On this computer. */
const val MUSIC_FOLDER_KEY = "desktop_music_folder"

private val AUDIO = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "aif", "aiff", "ape", "wv", "mka", "alac")
private val FOLDER_ART = listOf("cover", "folder", "front", "album")

/** What one file's tags say, kept with the file's size and time so an unchanged file isn't read again. */
private data class Tags(val stamp: String, val title: String?, val artist: String?, val ms: Long, val art: Boolean)

/**
 * The music files in a folder and every folder inside it, A to Z by song name, read with ffprobe. A song's id is
 * "local:" and the file's path, so it plays straight from the file. Read again at start, when Saved opens and when the
 * folder changes; tags are cached in [cache], so a second read only lists the folder.
 * ponytail: no live watching; a file added while the app runs shows when Saved is opened again.
 */
class MusicFolder(private val cache: File, private val prefs: Prefs, hideNonMusic: StateFlow<Boolean>) {
    val folder = MutableStateFlow(prefs.getString(MUSIC_FOLDER_KEY, null)?.let(::File) ?: defaultMusicDir())
    private val rows = MutableStateFlow<List<Pair<AudioRow, Song>>>(emptyList())
    val songs: StateFlow<List<Song>> = combine(rows, hideNonMusic) { r, hide -> r.filter { !hide || !it.first.isNonMusic() }.map { it.second } }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, emptyList())
    /** True while a read is running, for the Settings line. */
    val reading = MutableStateFlow(false)
    private val lock = Mutex()
    private val known = HashMap<String, Tags>()

    fun choose(dir: File) {
        prefs.edit().putString(MUSIC_FOLDER_KEY, dir.path).apply()
        folder.value = dir
    }

    suspend fun refresh() = lock.withLock {
        reading.value = true
        try {
            rows.value = withContext(Dispatchers.IO) { read(folder.value) }
        } finally { reading.value = false }
    }

    private suspend fun read(dir: File): List<Pair<AudioRow, Song>> = kotlinx.coroutines.coroutineScope {
        if (known.isEmpty()) loadCache()
        val files = dir.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile && it.extension.lowercase() in AUDIO }.toList()
        val probe = ffprobe != null
        val limited = Dispatchers.IO.limitedParallelism(4)
        val found = files.map { f ->
            async(limited) {
                val stamp = "${f.length()}:${f.lastModified()}"
                val tags = known[f.path]?.takeIf { it.stamp == stamp } ?: probe(f, stamp)
                f to tags
            }
        }.awaitAll()
        synchronized(known) { known.clear(); found.forEach { (f, t) -> known[f.path] = t } }
        saveCache()
        found.map { (f, t) ->
            val (guessArtist, guessTitle) = nameParts(f.nameWithoutExtension)
            // without ffprobe the length is unknown: count it as long enough, so nothing is hidden as a clip
            AudioRow(if (probe) t.ms else 60_000, null, f.path, system = false, recording = false) to Song(
                id = LOCAL_PREFIX + f.path,
                title = t.title?.ifBlank { null } ?: guessTitle,
                artist = t.artist?.ifBlank { null } ?: guessArtist ?: "Unknown artist",
                durationSec = t.ms / 1000,
                thumbnailUrl = folderArt(f.parentFile)?.let { "file://${it.path}" } ?: if (t.art) "file://${f.path}" else null,
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second.title })
    }

    private fun probe(f: File, stamp: String): Tags {
        val tool = ffprobe ?: return Tags(stamp, null, null, 0, false)
        val json = runCatching {
            val p = ProcessBuilder(tool, "-v", "quiet", "-print_format", "json", "-show_entries", "format=duration:format_tags:stream=codec_type:stream_tags", f.path)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            p.inputStream.bufferedReader().readText().also { p.waitFor() }
        }.getOrNull()
        return parseProbe(json, stamp)
    }

    private fun loadCache() = runCatching {
        val o = JSONObject(cache.readText())
        o.keys().forEach { k ->
            val t = o.getJSONObject(k)
            known[k] = Tags(t.getString("s"), t.optString("t").ifEmpty { null }, t.optString("a").ifEmpty { null }, t.optLong("ms"), t.optBoolean("art"))
        }
    }

    private fun saveCache() = runCatching {
        val o = JSONObject()
        synchronized(known) { known.forEach { (k, t) -> o.put(k, JSONObject().put("s", t.stamp).put("t", t.title ?: "").put("a", t.artist ?: "").put("ms", t.ms).put("art", t.art)) } }
        cache.parentFile?.mkdirs()
        cache.writeText(o.toString())
    }

    companion object {
        private val ffprobe: String? by lazy { Platform.find("ffprobe") }
        private val ffmpeg: String? by lazy { Platform.find("ffmpeg") }

        /** A cover picture lying next to the songs: cover.jpg, folder.png and the like. */
        private fun folderArt(dir: File?): File? = dir?.listFiles()?.firstOrNull {
            it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") && it.nameWithoutExtension.lowercase() in FOLDER_ART
        }

        /** The picture of a file:// cover (an image, or a song with one inside), made 320 wide so a long list stays light. */
        fun art(path: String): ByteArray? {
            val image = path.substringAfterLast('.').lowercase() !in AUDIO
            val tool = ffmpeg ?: return if (image) runCatching { File(path).readBytes() }.getOrNull() else null
            return runCatching {
                val p = ProcessBuilder(tool, "-v", "quiet", "-i", path, "-map", "0:v:0", "-frames:v", "1", "-vf", "scale=320:-2", "-f", "image2pipe", "-c:v", "mjpeg", "-")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                p.inputStream.readBytes().takeIf { p.waitFor() == 0 && it.isNotEmpty() }
            }.getOrNull()
        }

        /** "Artist - Title" from a file name, or just the name as the title. A leading track number is dropped. */
        internal fun nameParts(name: String): Pair<String?, String> {
            val clean = name.replace(Regex("^\\d{1,3}[ ._-]+"), "").trim()
            val parts = clean.split(" - ", limit = 2)
            return if (parts.size == 2 && parts.all { it.isNotBlank() }) parts[0].trim() to parts[1].trim() else null to clean.ifEmpty { name }
        }

        /** Title, artist, length and whether there is a picture inside, from ffprobe's answer. Tags are read in any case. */
        private fun parseProbe(json: String?, stamp: String): Tags = runCatching {
            val o = JSONObject(json!!)
            val format = o.optJSONObject("format")
            val streams = o.optJSONArray("streams")
            val tagSets = listOfNotNull(format?.optJSONObject("tags")) + (0 until (streams?.length() ?: 0)).mapNotNull { streams!!.getJSONObject(it).optJSONObject("tags") }
            fun tag(name: String) = tagSets.firstNotNullOfOrNull { t -> t.keys().asSequence().firstOrNull { it.equals(name, true) }?.let { t.getString(it) } }
            val art = (0 until (streams?.length() ?: 0)).any { streams!!.getJSONObject(it).optString("codec_type") == "video" }
            Tags(stamp, tag("title"), tag("artist"), ((format?.optString("duration")?.toDoubleOrNull() ?: 0.0) * 1000).toLong(), art)
        }.getOrDefault(Tags(stamp, null, null, 0, false))
    }
}

/** The desktop's music folder (xdg-user-dir MUSIC on Linux), or ~/Music. */
fun defaultMusicDir(): File = Platform.userFolder("Music")
