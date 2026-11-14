package com.spotifry.music.data

import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.source.isLocal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches resolved stream URLs. A cached URL is reused only while it has more than a minute left. A song being
 * looked up already (fetched ahead, then tapped) is waited for, not looked up twice.
 */
class StreamResolver(
    private val source: MusicSource,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cache = ConcurrentHashMap<String, ResolvedStream>()
    private val pending = ConcurrentHashMap<String, Deferred<ResolvedStream>>()
    @Volatile private var clears = 0

    suspend fun get(song: Song, low: Boolean = false, patient: Boolean = false, prefer: String? = null): ResolvedStream {
        val key = if (low) "${song.id}|low" else song.id
        cache[key]?.let { if (it.expiresAtMs - now() > FRESH_MARGIN_MS && (prefer == null || it.origin == prefer)) return it }
        // started only once stored, so a lookup that ends at once can't remove itself before it is in the map
        // under its clear count, so a lookup from before a clear is neither joined nor kept
        val asOf = clears
        val running = "$asOf|$key|$prefer|${patient && source.waitsForBest}"
        val job = pending.computeIfAbsent(running) {
            scope.async(start = CoroutineStart.LAZY) {
                try { (if (!patient && prefer == null) source.resolve(song, low) else source.resolve(song, low, patient, prefer)).also { if (asOf == clears) cache[key] = it } } finally { pending.remove(running) }
            }
        }
        return job.await()
    }

    suspend fun prefetch(song: Song, low: Boolean = false) {
        if (song.isLocal) return // a file on the phone: nothing to fetch
        try { get(song, low, patient = true) } catch (_: Exception) { }
    }

    /** Replaces the link kept for a song, when a better copy of it has been found. */
    fun put(song: Song, stream: ResolvedStream) { cache[song.id] = stream }

    /** Looks [songs] up in the background, so a tap on one of them starts without waiting for YouTube. */
    fun warm(songs: List<Song>, low: Boolean = false) = songs.forEach { scope.launch { prefetch(it, low) } }

    /** The source's bitrate for a song looked up in this run, for the quality label. Null when it played from the cache or a file. */
    /** [origin]: the source of the file really playing; a figure noted for another source's copy of the song doesn't count. */
    fun knownKbps(songId: String, origin: String? = null): Int? =
        (cache[songId] ?: cache["$songId|low"])?.takeIf { origin == null || it.origin == origin }?.bitrateKbps?.takeIf { it > 0 }

    fun invalidate(songId: String) { cache.remove(songId); cache.remove("$songId|low") }

    /** Forgets every link, for when the songs should come from elsewhere from now on. */
    fun clear() { clears++; cache.clear() }

    private companion object { const val FRESH_MARGIN_MS = 60_000L }
}
