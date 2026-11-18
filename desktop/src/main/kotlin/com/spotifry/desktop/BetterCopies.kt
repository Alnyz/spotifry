package com.spotifry.desktop

import com.spotifry.music.data.source.ChoosingSource
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.source.isLocal
import com.spotifry.music.data.source.qualityRank
import com.spotifry.music.player.UPGRADE_RETRY_MS
import com.spotifry.music.player.shouldLook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
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
    /** The songs playing or next, as last told by [upgradeWhenFree]. */
    @Volatile private var busyNow = emptySet<String>()
    private var retrying: Job? = null

    // copies finished before the app last closed (their song id is the file's name, which a YouTube id keeps as it is)
    init { for ((id, origin, file) in replay.waitingBetter()) ready[id] = file to origin }

    /** A better copy than the one in use turned up; the best one offered is kept. */
    fun offer(song: Song, stream: ResolvedStream) {
        say("${stream.origin} copy offered for ${song.id}")
        offers.merge(song.id, song to stream) { old, new -> if (qualityRank(new.second.origin) > qualityRank(old.second.origin)) new else old }
    }

    /** Song [song], playing from [origin], has been heard for 30 seconds: look for a better copy if there can be one, and download it. */
    suspend fun onHeard(song: Song, origin: String?) {
        if (song.isLocal || origin == null || qualityRank(origin) == 0) return // a file of the listener's, or a saved song
        if (shouldLook(origin, source.bestRank(), offers[song.id]?.second?.origin, tried[song.id], now())) {
            tried[song.id] = now()
            val found = source.findBetter(song, origin)
            say("looked for a better copy of ${song.id} than $origin: ${found?.origin ?: "none"}")
            // nothing came back (a slow or refusing server counts too): ask again in 5 minutes, not 30
            if (found == null) { tried[song.id] = now() - UPGRADE_RETRY_MS + 5 * 60_000L; askAgain(song, origin) } else offer(song, found)
        }
        download(song)
    }

    /**
     * A look that found nothing is often the server saying "not now" (asked too much, a timeout), which it remembers for a
     * few minutes. Asked again in the background past those minutes, so the song needn't be played again first.
     */
    private fun askAgain(song: Song, origin: String) = scope.launch {
        for (wait in ASK_AGAIN_MS) {
            kotlinx.coroutines.delay(wait)
            if (offers.containsKey(song.id) || ready.containsKey(song.id)) return@launch
            val found = source.findBetter(song, origin)
            say("asked again for a better copy of ${song.id}: ${found?.origin ?: "none"}")
            if (found != null) { offer(song, found); download(song); return@launch }
        }
    }

    /** Downloads the better copy offered for [song] whole, in the background. */
    fun download(song: Song) {
        val (_, stream) = offers[song.id] ?: return
        if (ready.containsKey(song.id) || !downloading.add(song.id)) return
        note()
        scope.launch(Dispatchers.IO) {
            try {
                loader.withLock {
                    val done = replay.incoming(song.id, stream.origin)
                    val part = File(done.path + ".part")
                    say("downloading ${stream.origin} copy of ${song.id}")
                    val why = fetchResuming(client, stream.url, part, { bufferedAheadMs() >= LOW_BUFFER_MS }, ::say)
                        ?: "could not rename".takeUnless { part.renameTo(done) }
                    if (why == null) { ready[song.id] = done to stream.origin; say("${stream.origin} copy of ${song.id} done, ${done.length() / 1024} KB") }
                    else {
                        part.delete()
                        say("${stream.origin} copy of ${song.id} failed: $why")
                        // the link may have run out: the next look (after the next 30 s heard, no sooner than 5 minutes) asks for a new one
                        offers.remove(song.id)
                        tried[song.id] = now() - UPGRADE_RETRY_MS + 5 * 60_000L
                    }
                }
            } finally { downloading.remove(song.id); note() }
        }
    }

    /**
     * The player moved on: finished copies go in place, except for the songs [busy] (playing or next). The store says no
     * while the song before still fades out from it, so a copy turned down is tried again every few seconds for 2 minutes.
     */
    fun upgradeWhenFree(busy: Set<String>) {
        busyNow = busy
        if (installReady(loud = true) || retrying?.isActive == true) return
        retrying = scope.launch { repeat(24) { kotlinx.coroutines.delay(5_000); if (installReady(loud = false)) return@launch } }
    }

    /** Puts the finished copies of songs not busy in place; true when none of those is left. */
    @Synchronized private fun installReady(loud: Boolean): Boolean {
        for (id in ready.keys.toList()) {
            if (id in busyNow) continue
            val (file, origin) = ready[id] ?: continue
            val ok = replay.install(id, origin, file)
            if (ok || loud) say("$origin copy of $id ${if (ok) "in place" else "waits, the song still plays"}")
            if (ok) { ready.remove(id); offers.remove(id) }
        }
        note()
        return ready.keys.all { it in busyNow }
    }

    private fun say(text: String) = System.err.println("${java.time.LocalTime.now().withNano(0)} better: $text")

    private companion object {
        const val LOW_BUFFER_MS = 20_000L
        /** Past the server's 1-minute rest after "too many", then past its 5-minute rest and the 10 minutes a "not found" is kept. */
        val ASK_AGAIN_MS = listOf(90_000L, 11 * 60_000L)
    }
}

/**
 * Downloads [url] whole into [part], waiting while [mayGo] is false (the playing song has little ready). A cut connection (a slow
 * server, a read that timed out) goes on from where it got, as replay storage does. Null when whole, else why not.
 */
internal fun fetchResuming(client: OkHttpClient, url: String, part: File, mayGo: () -> Boolean, say: (String) -> Unit = {}): String? {
    part.delete()
    val link = rangeInLink(url)
    var total = -1L
    var fails = 0
    var why: String? = null
    while (total < 0 || part.length() < total) {
        val have = part.length()
        try {
            client.newCall(ranged(url, have, null)).execute().use { r ->
                check(r.code == 206 || r.code == 200) { "answer ${r.code}" }
                val body = r.body!!
                // YouTube answers a range in the link with just that part; a server ignoring the Range header sends it all again
                val again = !link && r.code == 200
                val size = if (link) body.contentLength().takeIf { it >= 0 }?.plus(have) ?: -1L
                    else r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: body.contentLength()
                check(size > 0) { "no length" }
                check(total < 0 || size == total) { "the file changed" }
                total = size
                FileOutputStream(part, !again).use { out ->
                    val input = body.byteStream(); val buf = ByteArray(64 * 1024)
                    while (true) {
                        while (!mayGo()) Thread.sleep(300)
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) { why = e.toString() }
        if (part.length() > have) fails = 0
        else if (++fails > 8) return why ?: "no progress"
        // "too many" (429) is the server's rest of about a minute; asking sooner only keeps it going
        else Thread.sleep(if (why?.endsWith("answer 429") == true) RATE_LIMIT_WAIT_MS else 1000L * fails)
        if (total >= 0 && part.length() < total) say("going on from ${part.length() / 1024} KB of ${total / 1024}: $why")
    }
    return if (part.length() == total) null else "longer than said"
}

private const val RATE_LIMIT_WAIT_MS = 60_000L
