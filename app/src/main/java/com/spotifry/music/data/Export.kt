package com.spotifry.music.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/** "Artist - Title.ext", safe as a file name. */
fun exportName(title: String, artist: String, ext: String): String {
    val by = artist.removeSuffix(" - Topic").trim()
    return (if (by.isEmpty()) title else "$by - $title").replace(Regex("[\\\\/:*?\"<>|]"), "_").trim() + ".$ext"
}

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

/**
 * Copies a saved song to Music/Spotifry (or Download/Spotifry, see [exportPlace]), where file managers and other players
 * see it. Returns the folder it went to, or null if it could not be copied; it never throws, so a phone that refuses
 * cannot take the app down. Run off the main thread.
 */
fun exportToMusic(context: Context, path: String, title: String, artist: String): String? {
    if (!canExport) return null
    val resolver = context.contentResolver
    var uri: android.net.Uri? = null
    return try {
        val file = File(path).takeIf { it.isFile } ?: return null
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
