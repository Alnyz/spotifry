package com.spotifry.music.data

import android.Manifest
import android.os.Build
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** The permission that lets the app read music files: its own on Android 13+, general storage before that. */
val AUDIO_PERMISSION: String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

/**
 * The music files already on the phone, read from Android's media list (MediaStore), A to Z by song name.
 * Empty until the user allows access to music.
 * ponytail: read again only when the app starts or the "On this phone" tab opens; a ContentObserver if new files should appear live.
 */
class LocalMusic(private val context: Context) {
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs

    fun allowed() = context.checkSelfPermission(AUDIO_PERMISSION) == PackageManager.PERMISSION_GRANTED

    suspend fun refresh() {
        if (!allowed()) return
        _songs.value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID),
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null,
                )?.use { c ->
                    buildList { while (c.moveToNext()) add(localSong(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4))) }
                }.orEmpty().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            }.getOrDefault(emptyList())
        }
    }

    companion object {
        /** Where the file of a local song id is read from; null for YouTube songs. */
        fun uriOf(id: String): Uri? = id.removePrefix("local:").takeIf { it != id }?.toLongOrNull()
            ?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
    }
}

/** One MediaStore row as a song. The cover is the album art Android pulls out of the file. */
fun localSong(id: Long, title: String?, artist: String?, durationMs: Long, albumId: Long) = Song(
    id = "local:$id",
    title = title?.ifBlank { null } ?: "Unknown song",
    artist = artist?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING } ?: "Unknown artist",
    durationSec = durationMs / 1000,
    thumbnailUrl = "content://media/external/audio/albumart/$albumId",
)

/** Up to [max] songs whose name and artist together hold every word of [query], in list order. */
fun localMatches(songs: List<Song>, query: String, max: Int = 3): List<Song> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return songs.asSequence().filter { s -> words.all { w -> s.title.contains(w, true) || s.artist.contains(w, true) } }.take(max).toList()
}
