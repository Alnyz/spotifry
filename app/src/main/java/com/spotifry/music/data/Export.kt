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

fun exportMime(ext: String) = when (ext) { "flac" -> "audio/flac"; "webm" -> "audio/webm"; else -> "audio/mp4" }

/** Copying into the shared Music folder needs no permission only from Android 10; before that the menu hides the option. */
val canExport get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

/** Copies a saved song to Music/Spotifry, where file managers and other players see it. False if it could not be copied. Run off the main thread. */
fun exportToMusic(context: Context, path: String, title: String, artist: String): Boolean {
    if (!canExport) return false
    val file = File(path).takeIf { it.isFile } ?: return false
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, exportName(title, artist, file.extension))
        put(MediaStore.Audio.Media.MIME_TYPE, exportMime(file.extension))
        put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Spotifry")
        put(MediaStore.Audio.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        true
    } catch (e: Exception) {
        resolver.delete(uri, null, null) // no half file left in the Music folder
        false
    }
}
