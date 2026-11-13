package com.spotifry.music.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.spotifry.music.data.StreamResolver
import com.spotifry.music.data.db.SongDao
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

/**
 * A saved song becomes its file, a song from the phone its content:// file (both skip the song cache);
 * any other song passes through unchanged, to the song cache.
 */
@UnstableApi
class SavedFileResolver(private val songs: SongDao) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        dataSpec.key?.let(com.spotifry.music.data.LocalMusic::uriOf)?.let { return dataSpec.withUri(it) }
        val path = dataSpec.key?.let { id -> runBlocking { songs.get(id)?.filePath } } ?: return dataSpec
        return dataSpec.withUri(Uri.fromFile(File(path)))
    }
}

/**
 * Turns a song id (the DataSpec key) into something playable at load time:
 * the saved file if there is one, otherwise a fresh stream URL.
 */
@UnstableApi
class SongDataSourceResolver(
    private val resolver: StreamResolver,
    private val songs: SongDao,
    /** Asked at each load, so turning on data saver or leaving Wi-Fi applies from the next song. */
    private val lowQuality: () -> Boolean = { false },
    /** The song cache in front of this; null when there is none. */
    private val cache: androidx.media3.datasource.cache.Cache? = null,
) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val id = dataSpec.key ?: throw IOException("missing song id")
        // runs on ExoPlayer's loading thread, so blocking here is fine
        return runBlocking {
            val saved = songs.get(id)
            val path = saved?.filePath
            if (path != null) {
                dataSpec.withUri(Uri.fromFile(File(path)))
            } else {
                try {
                    val s = saved?.toSong() ?: songFromUri(id, dataSpec.uri)
                    val stream = resolver.get(s, lowQuality(), prefer = keptOrigin(id))
                    keepCacheToOneSource(id, stream.origin)
                    dataSpec.withUri(Uri.parse(stream.url))
                } catch (e: java.io.FileNotFoundException) {
                    throw e // not worth retrying as it is: NoAudioException skips to the next song, SourceChanged starts over
                } catch (e: Exception) {
                    throw IOException("could not resolve $id", e)
                }
            }
        }
    }

    /**
     * The cache joins what it has of a song to the rest from the link, so both must be the same file. A song half
     * played from one source and now coming from the other (the switch, or a slow lookup) starts over from the new one.
     * Nothing recorded means YouTube: that's all the cache held before there was a choice.
     */
    /** The source of the bytes the cache already holds for the song, so the lookup carries on from the same one. */
    private fun keptOrigin(id: String): String? {
        val c = cache ?: return null
        if (c.getCachedSpans(id).isEmpty()) return null
        return c.getContentMetadata(id).get(SOURCE_KEY, null as String?) ?: "youtube"
    }

    private fun keepCacheToOneSource(id: String, from: String) {
        val c = cache ?: return
        val had = c.getContentMetadata(id).get(SOURCE_KEY, null as String?)
        if ((had ?: "youtube") != from && c.getCachedSpans(id).isNotEmpty()) {
            c.removeResource(id)
            c.applyContentMetadataMutations(id, androidx.media3.datasource.cache.ContentMetadataMutations().set(SOURCE_KEY, from))
            throw SourceChangedException(id)
        }
        if (had != from) c.applyContentMetadataMutations(id, androidx.media3.datasource.cache.ContentMetadataMutations().set(SOURCE_KEY, from))
    }

    private companion object { const val SOURCE_KEY = SONG_SOURCE_KEY }
}

/** The cache's note on which source a song's bytes are from. */
const val SONG_SOURCE_KEY = "custom_source"

/** A FileNotFoundException, so the player doesn't retry it from the same position; PlaybackService starts the song's loading over. */
class SourceChangedException(val songId: String) : java.io.FileNotFoundException("$songId now comes from another source")

/** The error is about another song than [currentId], the next one loading ahead: the current song did nothing wrong. */
fun sourceChangedOnOtherSong(error: Throwable, currentId: String): Boolean =
    generateSequence(error) { it.cause }.filterIsInstance<SourceChangedException>().any { it.songId != currentId }

/** The saved copy of the song is damaged (cut short or unreadable), so playing it again from the same bytes can't work. */
fun savedCopyBroken(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { it is java.io.EOFException || it is androidx.media3.common.ParserException }
