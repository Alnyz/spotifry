package com.spotifry.music.data

import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.source.isLocal
import java.util.concurrent.ConcurrentHashMap

/** Caches resolved stream URLs. A cached URL is reused only while it has more than a minute left. */
class StreamResolver(
    private val source: MusicSource,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // ponytail: no lock, two callers racing on one song may resolve twice; harmless, add a per-id lock if it shows up in logs
    private val cache = ConcurrentHashMap<String, ResolvedStream>()

    suspend fun get(song: Song, low: Boolean = false): ResolvedStream {
        val key = if (low) "${song.id}|low" else song.id
        cache[key]?.let { if (it.expiresAtMs - now() > FRESH_MARGIN_MS) return it }
        return source.resolve(song, low).also { cache[key] = it }
    }

    suspend fun prefetch(song: Song, low: Boolean = false) {
        if (song.isLocal) return // a file on the phone: nothing to fetch
        try { get(song, low) } catch (_: Exception) { }
    }

    fun invalidate(songId: String) { cache.remove(songId); cache.remove("$songId|low") }

    private companion object { const val FRESH_MARGIN_MS = 60_000L }
}
