package com.spotifry.music.player

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.spotifry.music.data.source.Song

private const val KEY_DURATION = "durationSec"
private const val KEY_REMOTE_ART = "remoteArt"
private const val KEY_LOCAL_ART = "localArt"
private const val KEY_SUGGESTED = "suggested"

/** Each song's own start and end, looked up by id; the app points this at its [Trims] when it starts. */
var trimOf: (String) -> Trim? = { null }

/**
 * The URI is a placeholder: SongDataSourceResolver swaps it for a file or stream URL at load time. With a [trim] the
 * player only sees the part in between, so the bar, the fade and the move to the next song all follow it.
 */
fun Song.toMediaItem(trim: Trim? = trimOf(id)): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .apply {
        if (trim != null && !trim.isWhole) setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(trim.startMs).apply { trim.endMs?.let(::setEndPositionMs) }.build(),
        )
    }
    .setUri(Uri.Builder().scheme("music").authority("song").appendPath(id).appendQueryParameter("t", title).appendQueryParameter("a", artist).appendQueryParameter("d", durationSec.toString()).build())
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

/** The song named in the [toMediaItem] URI, for a first play: its row in the songs table is written about the same time as the load starts. */
fun songFromUri(id: String, uri: Uri) = Song(id, uri.getQueryParameter("t").orEmpty(), uri.getQueryParameter("a").orEmpty(), uri.getQueryParameter("d")?.toLongOrNull() ?: 0, null)

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

/** The trim this item was built with, or null when it plays the whole song. */
val MediaItem.trim: Trim?
    get() = Trim(clippingConfiguration.startPositionMs, clippingConfiguration.endPositionMs.takeIf { it != C.TIME_END_OF_SOURCE }).takeUnless { it.isWhole }
