package com.spotifry.music.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Where a copy goes. Android's media library takes flac and m4a as music, but reads a .webm (the small Opus songs from
 * YouTube) as video and refuses it in Music, so those go to Download/Spotifry, where a file manager still shows them.
 */
class ExportPlace(val folder: String, val mime: String, val collection: ExportTo)
enum class ExportTo { Music, Downloads }

fun exportPlace(ext: String): ExportPlace = when (ext.lowercase()) {
    "flac" -> ExportPlace("Music/Spotifry", "audio/flac", ExportTo.Music)
    "m4a" -> ExportPlace("Music/Spotifry", "audio/mp4", ExportTo.Music)
    "mp3" -> ExportPlace("Music/Spotifry", "audio/mpeg", ExportTo.Music)
    "webm" -> ExportPlace("Download/Spotifry", "video/webm", ExportTo.Downloads)
    else -> ExportPlace("Download/Spotifry", "application/octet-stream", ExportTo.Downloads)
}

/** Copying into the shared Music and Download folders needs no permission only from Android 10; before that the menu hides the option. */
val canExport get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

/** Counts every copy made, so a screen showing which songs are copied reads again. */
val exportTick = kotlinx.coroutines.flow.MutableStateFlow(0)

/**
 * True when a copy of the saved song is already in Music/Spotifry or Download/Spotifry (its Opus copy counts for a .webm).
 * Looks only at what this app made, which needs no permission. False on any trouble, so a failed look never hides a song. Run off the main thread.
 */
fun isExported(context: Context, path: String, title: String, artist: String): Boolean {
    if (!canExport) return false
    val ext = File(path).extension
    val names = buildList {
        add(exportName(title, artist, ext) to exportPlace(ext))
        if (ext.equals("webm", true)) add(exportName(title, artist, "opus") to ExportPlace(OPUS_FOLDER, "audio/ogg", ExportTo.Music))
    }
    return try {
        names.any { (name, place) ->
            val table = if (place.collection == ExportTo.Music) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            context.contentResolver.query(
                table, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf(name, place.folder + "%"), null,
            )?.use { it.moveToFirst() } == true
        }
    } catch (e: Exception) { false }
}

/**
 * Copies a saved song to Music/Spotifry (or Download/Spotifry, see [exportPlace]), where file managers and other players
 * see it. Returns the folder it went to, or null if it could not be copied; it never throws, so a phone that refuses
 * cannot take the app down. Run off the main thread.
 */
@android.annotation.SuppressLint("NewApi") // only reached when canExport (Android 10+): the menu hides the option before that
fun exportToMusic(context: Context, path: String, title: String, artist: String): String? {
    if (!canExport) return null
    return copyOut(context, path, title, artist)?.also { exportTick.value++ }
}

private fun copyOut(context: Context, path: String, title: String, artist: String): String? {
    val resolver = context.contentResolver
    var uri: android.net.Uri? = null
    return try {
        val file = File(path).takeIf { it.isFile } ?: return null
        // a .webm's Opus sound moved into a real Opus file, which Android counts as music; if the phone can't, the .webm copy below is the fallback
        if (file.extension.equals("webm", true)) exportAsOpus(resolver, file, title, artist)?.let { return it }
        val place = exportPlace(file.extension)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, exportName(title, artist, file.extension))
            put(MediaStore.MediaColumns.MIME_TYPE, place.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, place.folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val table = if (place.collection == ExportTo.Music) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val made = resolver.insert(table, values) ?: return null
        uri = made
        resolver.openOutputStream(made)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        resolver.update(made, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        place.folder
    } catch (e: Exception) {
        uri?.let { runCatching { resolver.delete(it, null, null) } } // no half file left behind
        null
    }
}

/** Where an Opus song goes once it is a real Opus file. */
private const val OPUS_FOLDER = "Music/Spotifry"

/**
 * The .webm's Opus sound written into an Ogg Opus file in Music/Spotifry by Android's own muxer (Android 10 and up),
 * with nothing re-encoded. Returns the folder, or null when the phone could not do it; a half-made file is removed.
 */
@android.annotation.SuppressLint("NewApi") // Ogg muxing: Android 10+, called only from exportToMusic
private fun exportAsOpus(resolver: android.content.ContentResolver, file: File, title: String, artist: String): String? {
    var uri: android.net.Uri? = null
    return try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, exportName(title, artist, "opus"))
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/ogg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, OPUS_FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val made = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        uri = made
        val written = resolver.openFileDescriptor(made, "rw")!!.use { pfd -> webmToOgg(file, pfd.fileDescriptor) && pfd.statSize > 0 }
        if (!written) { resolver.delete(made, null, null); return null }
        resolver.update(made, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        OPUS_FOLDER
    } catch (e: Exception) {
        uri?.let { runCatching { resolver.delete(it, null, null) } }
        null
    }
}

/** Moves the Opus track of [source] (a .webm) into an Ogg file at [out], sample by sample. False if there is no Opus track or the muxer refuses. */
private fun webmToOgg(source: File, out: java.io.FileDescriptor): Boolean {
    val extractor = android.media.MediaExtractor()
    var muxer: android.media.MediaMuxer? = null
    return try {
        extractor.setDataSource(source.path)
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME) == "audio/opus" } ?: return false
        extractor.selectTrack(track)
        val m = android.media.MediaMuxer(out, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
        muxer = m
        val index = m.addTrack(extractor.getTrackFormat(track))
        m.start()
        val buffer = java.nio.ByteBuffer.allocate(1 shl 18)
        val info = android.media.MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime, if (extractor.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            m.writeSampleData(index, buffer, info)
            extractor.advance()
        }
        m.stop()
        true
    } catch (e: Exception) {
        false
    } finally {
        runCatching { muxer?.release() }
        extractor.release()
    }
}
