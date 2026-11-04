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

/** A saved song becomes its file; any other song passes through unchanged, to the song cache. */
@UnstableApi
class SavedFileResolver(private val songs: SongDao) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
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
                    val s = saved?.toSong() ?: Song(id, "", "", 0, null)
                    dataSpec.withUri(Uri.parse(resolver.get(s, lowQuality()).url))
                } catch (e: Exception) {
                    throw IOException("could not resolve $id", e)
                }
            }
        }
    }
}
