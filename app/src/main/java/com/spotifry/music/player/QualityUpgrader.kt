package com.spotifry.music.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadataMutations
import com.spotifry.music.data.StreamResolver
import com.spotifry.music.data.source.ChoosingSource
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.source.isLocal
import com.spotifry.music.data.source.qualityRank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * A song that started from YouTube because it was the quickest answer is stored there as YouTube's copy. Once it has
 * been heard for 30 seconds, this looks for the lossless or JioSaavn copy, and when one exists, swaps the stored copy
 * for it between plays (never under a song being heard) and only on a connection that doesn't count data.
 * One download at a time.
 */
@UnstableApi
class QualityUpgrader(
    private val cache: Cache,
    private val source: ChoosingSource,
    private val resolver: StreamResolver,
    private val upstream: DataSource.Factory,
    private val onUnmetered: () -> Boolean,
    private val isSaved: suspend (String) -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val offers = ConcurrentHashMap<String, Pair<Song, ResolvedStream>>()
    private val tried = ConcurrentHashMap<String, Long>()
    private val one = Mutex()
    /** The songs playing or next, as of the last song change. ponytail: a song that starts after a swap has begun can still be caught; nothing guards that gap. */
    @Volatile private var busyNow: Set<String> = emptySet()

    /** A better copy than the one in use turned up; the best one offered is kept. */
    fun offer(song: Song, stream: ResolvedStream) {
        offers.merge(song.id, song to stream) { old, new -> if (qualityRank(new.second.origin) > qualityRank(old.second.origin)) new else old }
    }

    /** A song has been heard for 30 seconds: look for a better copy if the stored one isn't the best there can be. */
    suspend fun onHeard(song: Song) {
        if (song.isLocal || isSaved(song.id)) return
        val had = storedOrigin(song.id) ?: return
        val last = tried[song.id]
        if (!shouldLook(had, source.bestRank(), offers[song.id]?.second?.origin, last, now())) return
        tried[song.id] = now()
        source.findBetter(song, had)?.let { offer(song, it) }
    }

    /** Called when the player moves to another song: swaps in the copies offered, except for the songs [busy] (playing or next). */
    fun upgradeWhenFree(busy: Set<String>) {
        busyNow = busy
        if (!onUnmetered()) return
        for (id in offers.keys.toList()) {
            if (id in busy) continue
            val (song, better) = offers.remove(id) ?: continue
            // a swap waits its turn behind the one running, so the song may have started playing by then: ask again when it's its turn
            scope.launch { one.withLock { if (song.id in busyNow) offer(song, better) else runCatching { swap(song, better) } } }
        }
    }

    private fun storedOrigin(id: String): String? =
        if (cache.getCachedSpans(id).isEmpty()) null else cache.getContentMetadata(id).get(SONG_SOURCE_KEY, null as String?) ?: "youtube"

    private fun swap(song: Song, better: ResolvedStream) {
        cache.removeResource(song.id)
        cache.applyContentMetadataMutations(song.id, ContentMetadataMutations().set(SONG_SOURCE_KEY, better.origin))
        resolver.put(song, better)
        val data = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).createDataSource()
        CacheWriter(data, DataSpec.Builder().setUri(better.url).setKey(song.id).build(), null, null).cache()
    }
}

/** Whether to look for a better copy: the stored one isn't the best allowed, none is offered yet, and not asked for [RETRY_MS]. */
fun shouldLook(storedOrigin: String, bestAllowed: Int, offeredOrigin: String?, lastTried: Long?, now: Long): Boolean =
    qualityRank(storedOrigin) < bestAllowed && qualityRank(offeredOrigin) <= qualityRank(storedOrigin) && (lastTried == null || now - lastTried > RETRY_MS)

private const val RETRY_MS = 30 * 60 * 1000L
