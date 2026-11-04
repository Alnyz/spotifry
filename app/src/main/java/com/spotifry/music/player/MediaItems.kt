package com.spotifry.music.player

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.spotifry.music.data.source.Song

private const val KEY_DURATION = "durationSec"
private const val KEY_REMOTE_ART = "remoteArt"
private const val KEY_LOCAL_ART = "localArt"
private const val KEY_SUGGESTED = "suggested"

/** The URI is a placeholder: SongDataSourceResolver swaps it for a file or stream URL at load time. */
fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri("music://song/$id")
    .setCustomCacheKey(id)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setArtworkUri(localThumbPath?.takeIf { java.io.File(it).exists() }?.let { java.io.File(it).toUri() } ?: thumbnailUrl?.toUri())
            .setExtras(Bundle().apply { putLong(KEY_DURATION, durationSec); putString(KEY_REMOTE_ART, thumbnailUrl); putString(KEY_LOCAL_ART, localThumbPath); putBoolean(KEY_SUGGESTED, suggested) })
            .build()
    )
    .build()

fun MediaItem.toSong(): Song = Song(
    id = mediaId,
    title = mediaMetadata.title?.toString().orEmpty(),
    artist = mediaMetadata.artist?.toString().orEmpty(),
    durationSec = mediaMetadata.extras?.getLong(KEY_DURATION) ?: 0L,
    thumbnailUrl = mediaMetadata.extras?.getString(KEY_REMOTE_ART) ?: mediaMetadata.artworkUri?.takeIf { it.scheme != "file" }?.toString(),
    localThumbPath = mediaMetadata.extras?.getString(KEY_LOCAL_ART),
    suggested = isSuggested,
)

val MediaItem.isSuggested get() = mediaMetadata.extras?.getBoolean(KEY_SUGGESTED) == true
