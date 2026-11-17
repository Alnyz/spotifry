package com.spotifry.desktop

import com.spotifry.music.data.source.ChoosingSource
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.source.isLocal
import com.spotifry.music.data.source.qualityRank
import com.spotifry.music.player.UPGRADE_RETRY_MS
import com.spotifry.music.player.fetchWhole
import com.spotifry.music.player.shouldLook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's quality upgrade, for the desktop's kept songs ([ReplayStore]). A song that started from YouTube because it
 * answered first is kept as YouTube's copy. Once it has been heard for 30 seconds, the lossless or JioSaavn copy (offered
 * when it answered late, or looked for then) downloads whole in the background, waiting while the playing song has less
 * than 20 s ready, and takes the kept copy's place when the song isn't playing, so its next play is the better one.
 * One download at a time. ponytail: a desktop counts as unmetered; add a "Wi-Fi only" check if a metered desktop shows up.
 */
class BetterCopies(
    private val client: OkHttpClient,
    private val source: ChoosingSource,
    private val replay: ReplayStore,
    /** Seconds of the playing song ready ahead, in ms; a download waits while it is low, so the song heard gets the connection. */
    private val bufferedAheadMs: () -> Long,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val offers = ConcurrentHashMap<String, Pair<Song, ResolvedStream>>()
    private val tried = ConcurrentHashMap<String, Long>()
    /** Better copies done, waiting for their song to stop playing: id to the file and its source. */
    private val ready = ConcurrentHashMap<String, Pair<File, String>>()
    private val loader = Mutex()
    private val _coming = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Songs whose better copy is downloading or waiting to go in place: id to its source, for the Sound path panel. */
    val coming: StateFlow<Map<String, String>> = _coming
    private fun note() { _coming.value = (offers.mapValues { it.value.second.origin }.filterKeys { it in downloading } + ready.mapValues { it.value.second }) }
    private val downloading = ConcurrentHashMap.newKeySet<String>()

    /** A better copy than the one in use turned up; the best one offered is kept. */
    fun offer(song: Song, stream: ResolvedStream) {
        offers.merge(song.id, song to stream) { old, new -> if (qualityRank(new.second.origin) > qualityRank(old.second.origin)) new else old }
    }

    /** Song [song], playing from [origin], has been heard for 30 seconds: look for a better copy if there can be one, and download it. */
    suspend fun onHeard(song: Song, origin: String?) {
        if (song.isLocal || origin == null || qualityRank(origin) == 0) return // a file of the listener's, or a saved song
        if (shouldLook(origin, source.bestRank(), offers[song.id]?.second?.origin, tried[song.id], now())) {
            tried[song.id] = now()
            val found = source.findBetter(song, origin)
            // nothing came back (a slow or refusing server counts too): ask again in 5 minutes, not 30
            if (found == null) tried[song.id] = now() - UPGRADE_RETRY_MS + 5 * 60_000L else offer(song, found)
        }
        download(song)
    }

    /** Downloads the better copy offered for [song] whole, in the background. */
    fun download(song: Song) {
        val (_, stream) = offers[song.id] ?: return
        if (ready.containsKey(song.id) || !downloading.add(song.id)) return
        note()
        scope.launch(Dispatchers.IO) {
            try {
                loader.withLock {
                    val part = replay.incoming(song.id, stream.origin)
                    val ok = runCatching {
                        client.newCall(ranged(stream.url, 0, null)).execute().use { r ->
                            check(r.isSuccessful) { "answer ${r.code}" }
                            val body = r.body!!
                            // the whole file, or none of it: a download cut short is never taken for the song
                            val length = if (rangeInLink(stream.url)) body.contentLength() else r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: body.contentLength()
                            val input = body.byteStream()
                            fetchWhole(length, { b -> input.read(b) }, part) { bufferedAheadMs() >= LOW_BUFFER_MS }
                        }
                    }.getOrDefault(false)
                    if (ok) ready[song.id] = part to stream.origin
                    else {
                        part.delete()
                        // refused or cut off: asked for again after the next 30 s heard, no sooner than 5 minutes from now
                        tried[song.id] = now() - UPGRADE_RETRY_MS + 5 * 60_000L
                    }
                }
            } finally { downloading.remove(song.id); note() }
        }
    }

    /** The player moved on: finished copies go in place, except for the songs [busy] (playing or next). */
    fun upgradeWhenFree(busy: Set<String>) {
        for (id in ready.keys.toList()) {
            if (id in busy) continue
            val (file, origin) = ready[id] ?: continue
            // the store says no while the song still plays from it; it is tried again at the next song change
            if (replay.install(id, origin, file)) { ready.remove(id); offers.remove(id) }
        }
        note()
    }

    private companion object { const val LOW_BUFFER_MS = 20_000L }
}
