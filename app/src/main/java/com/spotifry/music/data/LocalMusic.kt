package com.spotifry.music.data

import android.Manifest
import android.os.Build
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media as M
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** The permission that lets the app read music files: its own on Android 13+, general storage before that. */
val AUDIO_PERMISSION: String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

/**
 * The music files already on the phone, read from Android's media list (MediaStore), A to Z by song name.
 * Empty until the user allows access to music.
 * ponytail: read again only when the app starts or the "On this phone" tab opens; a ContentObserver if new files should appear live.
 */
class LocalMusic(private val context: Context, hideNonMusic: StateFlow<Boolean> = MutableStateFlow(false)) {
    private val rows = MutableStateFlow<List<Pair<AudioRow, Song>>>(emptyList())
    // ponytail: lives as long as the app, like this object
    val songs: StateFlow<List<Song>> = combine(rows, hideNonMusic) { r, hide -> r.filter { !hide || !it.first.isNonMusic() }.map { it.second } }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, emptyList())

    fun allowed() = context.checkSelfPermission(AUDIO_PERMISSION) == PackageManager.PERMISSION_GRANTED

    suspend fun refresh() {
        if (!allowed()) return
        rows.value = withContext(Dispatchers.IO) {
            runCatching {
                val columns = listOf(M._ID, M.TITLE, M.ARTIST, M.DURATION, M.ALBUM_ID, M.MIME_TYPE, M.DATA, M.IS_RINGTONE, M.IS_NOTIFICATION, M.IS_ALARM) +
                    if (Build.VERSION.SDK_INT >= 31) listOf(M.IS_RECORDING) else emptyList()
                context.contentResolver.query(M.EXTERNAL_CONTENT_URI, columns.toTypedArray(), "${M.IS_MUSIC} != 0", null, null)?.use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            val row = AudioRow(
                                durationMs = c.getLong(3), mime = c.getString(5), path = c.getString(6),
                                system = c.getInt(7) != 0 || c.getInt(8) != 0 || c.getInt(9) != 0,
                                recording = columns.size > 10 && c.getInt(10) != 0,
                            )
                            add(row to localSong(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4)))
                        }
                    }
                }.orEmpty().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second.title })
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

/** What decides whether a MediaStore audio row is music: its length, type, place and the flags Android keeps. */
data class AudioRow(val durationMs: Long, val mime: String?, val path: String?, val system: Boolean, val recording: Boolean)

/** True for clips under 30 seconds, WAV files, recordings and voice notes, and ringtones, notifications and alarms. */
fun AudioRow.isNonMusic(): Boolean {
    val p = path.orEmpty().lowercase()
    val folder = p.substringBeforeLast('/')
    return durationMs < 30_000 || system || recording ||
        mime?.lowercase() in setOf("audio/x-wav", "audio/wav", "audio/wave", "audio/vnd.wave") || p.endsWith(".wav") ||
        listOf("recording", "recorder", "voice").any { folder.contains(it) }
}
