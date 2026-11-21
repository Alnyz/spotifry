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
    /** Songs known to have a better copy than the one stored: id to the origin ([ChoosingSource] asks that one first when the song plays). */
    private val better: android.content.SharedPreferences,
    private val onUnmetered: () -> Boolean,
    private val isSaved: suspend (String) -> Boolean,
    private val http: DataSource.Factory? = null,
    /** Where better copies download and wait to go in place; a whole one is kept across restarts (see [waitingCopies]). */
    private val dir: java.io.File? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val offers = ConcurrentHashMap<String, Pair<Song, ResolvedStream>>()
    private val tried = ConcurrentHashMap<String, Long>()
    private val one = Mutex()
    /** Better copies finished downloading, waiting for their song to stop playing: id to the file and its source. */
    private val ready = ConcurrentHashMap<String, Pair<java.io.File, String>>()
    private val downloading = ConcurrentHashMap.newKeySet<String>()
    private val loader = Mutex()
    /** Seconds of the playing song buffered ahead, set by the player. A download waits while it is low, so a weak connection goes to the song being heard. */
    @Volatile var bufferedAheadMs: Long = Long.MAX_VALUE
    // copies finished before Android closed the app go in place at the next song change instead of downloading again
    init { dir?.let { d -> d.mkdirs(); ready.putAll(waitingCopies(d, ::storedOrigin)) } }

    /** The songs playing or next, as of the last song change. ponytail: a song that starts after a swap has begun can still be caught; nothing guards that gap. */
    @Volatile private var busyNow: Set<String> = emptySet()

    private fun say(text: String) { android.util.Log.i("lossless", text) }

    /** A better copy than the one in use turned up; the best one offered is kept. */
    fun offer(song: Song, stream: ResolvedStream) {
        say("offered ${stream.origin} for ${song.id}")
        offers.merge(song.id, song to stream) { old, new -> if (qualityRank(new.second.origin) > qualityRank(old.second.origin)) new else old }
    }

    /** A song has been heard for 30 seconds: look for a better copy if the stored one isn't the best there can be. */
    suspend fun onHeard(song: Song) {
        if (song.isLocal || isSaved(song.id)) return
        val had = storedOrigin(song.id) ?: resolver.knownOrigin(song.id) ?: return say("heard ${song.id}: its source is not known, no look").let { }
        say("heard ${song.id} from $had")
        if (shouldLook(had, source.bestRank(), offers[song.id]?.second?.origin, tried[song.id], now())) {
            tried[song.id] = now()
            val found = source.findBetter(song, had)
            // nothing came back (a slow or refusing server counts too): ask again in 5 minutes, not 30
            if (found == null) { say("looked for a better copy of ${song.id} than $had: none"); tried[song.id] = now() - UPGRADE_RETRY_MS + 5 * 60_000L } else offer(song, found)
        }
        download(song)
    }

    /** A song has been heard for 30 seconds and a better copy is known: download it whole in the background, on a connection that doesn't count data. */
    fun download(song: Song) {
        val (_, stream) = offers[song.id] ?: return
        val http = http ?: return
        val dir = dir ?: return
        if (!onUnmetered()) return say("not downloading ${song.id}: the connection counts data")
        if (ready.containsKey(song.id) || !downloading.add(song.id)) return
        say("downloading ${stream.origin} copy of ${song.id}")
        scope.launch {
            try {
                loader.withLock {
                    val done = betterFile(dir, song.id, stream.origin)
                    val part = java.io.File(done.path + ".part")
                    part.delete()
                    val ok = runCatching { fetchResuming(http, stream.url, part) { bufferedAheadMs >= LOW_BUFFER_MS } }
                        .onFailure { say("download of ${song.id} failed: $it") }.getOrDefault(false)
                    if (ok) {
                        say("${stream.origin} copy of ${song.id} done, waits for the song to stop")
                        if (part.renameTo(done)) ready[song.id] = done to stream.origin else part.delete()
                    } else {
                        part.delete()
                        // refused or cut off: it is asked for again after the next 30 s heard, no sooner than 5 minutes from now
                        tried[song.id] = now() - UPGRADE_RETRY_MS + 5 * 60_000L
                    }
                }
            } finally { downloading.remove(song.id) }
        }
    }

    /**
     * Reads the whole file at [url] into [part], going on from where it stopped when the server cuts the connection (the
     * lossless server does, now and then), up to 8 tries in a row with no progress. True only when the size is known and it all came.
     */
    private fun fetchResuming(http: DataSource.Factory, url: String, part: java.io.File, mayGo: () -> Boolean): Boolean {
        var total = -1L; var fails = 0
        while (true) {
            val have = part.length()
            if (total in 0..have) return total == have
            var clean = false; var tooMany = false
            val src = http.createDataSource()
            try {
                val rest = src.open(DataSpec.Builder().setUri(url).setPosition(have).build())
                if (rest != androidx.media3.common.C.LENGTH_UNSET.toLong()) {
                    if (total >= 0 && have + rest != total) return false // the file changed
                    total = have + rest
                }
                java.io.FileOutputStream(part, true).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        while (!mayGo()) Thread.sleep(300)
                        val n = src.read(buf, 0, buf.size)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
                clean = true
            } catch (e: Exception) {
                tooMany = (e as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode == 429
                say("going on from ${have / 1024} KB of ${total / 1024}: $e".take(200))
            } finally { runCatching { src.close() } }
            if (clean && total < 0) return part.length() > 0 // no size given: a clean end is all there is
            // "too many" (429) is the server's rest of about a minute; asking sooner only keeps it going
            if (part.length() > have) fails = 0 else if (++fails > 8) return false else Thread.sleep(if (tooMany) 60_000L else 1000L * fails)
        }
    }

    /** Called when the player moves to another song: puts the finished copies in place, except for the songs [busy] (playing or next). */
    fun upgradeWhenFree(busy: Set<String>) {
        busyNow = busy
        for (id in ready.keys.toList()) {
            if (id in busy) continue
            val (file, origin) = ready.remove(id) ?: continue
            offers.remove(id)
            scope.launch { one.withLock { if (id in busyNow) ready[id] = file to origin else runCatching { install(id, file, origin) } } }
        }
    }

    /**
     * Lossless or JioSaavn was just turned on ([rank] is the best now allowed): the songs kept from a poorer source are
     * forgotten, so they come again from the better one, and what was offered or tried before is dropped. Not the song
     * playing or the one next, which would be cut under the listener; those are caught by the usual swap after 30 s heard.
     */
    fun forgetBelow(rank: Int) {
        offers.clear(); tried.clear()
        for (id in cache.keys.toList()) {
            if (id in busyNow) continue
            if (isBelow(storedOrigin(id), rank)) runCatching { cache.removeResource(id) }
        }
    }

    private fun storedOrigin(id: String): String? =
        if (cache.getCachedSpans(id).isEmpty()) null else cache.getContentMetadata(id).get(SONG_SOURCE_KEY, null as String?) ?: "youtube"

    /** The origin a song is known to have a better copy from, or null. */
    fun knownBetter(id: String): String? = better.getString(id, null)

    /** The better copy did not come when asked for (refused, gone): the song goes back to being looked for after 30 s heard. */
    fun forgetBetter(id: String) { better.edit().remove(id).apply(); tried.remove(id) }

    /**
     * The poorer stored copy is dropped and the finished file takes its place in the cache, under the song's key, so the
     * next play starts from it with no waiting on the server. Only a whole file gets here: a download cut short never
     * touches what is stored.
     */
    private fun install(id: String, file: java.io.File, origin: String) {
        try {
            cache.removeResource(id)
            cache.removeResource(id + OPUS_SUFFIX)
            cache.applyContentMetadataMutations(id, ContentMetadataMutations().set(SONG_SOURCE_KEY, origin))
            val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(androidx.media3.datasource.FileDataSource.Factory()).createDataSource()
            CacheWriter(source, DataSpec.Builder().setUri(android.net.Uri.fromFile(file)).setKey(id).build(), null, null).cache()
            this.better.edit().putString(id, origin).apply()
            say("$origin copy of $id in place")
        } finally { file.delete() }
    }
}

private const val LOW_BUFFER_MS = 20_000L

/** Where the whole better copy of song [id] from [origin] waits; it downloads as this name plus ".part". */
internal fun betterFile(dir: java.io.File, id: String, origin: String) =
    java.io.File(dir, "${java.net.URLEncoder.encode(id, "UTF-8")}.$origin.better")

/**
 * The whole better copies in [dir] still worth putting in place, as song id to the file and its origin. Everything else
 * goes: downloads cut short, and copies no better than the one stored now ([storedOrigin], null when none is stored).
 */
internal fun waitingCopies(dir: java.io.File, storedOrigin: (String) -> String?): Map<String, Pair<java.io.File, String>> {
    val keep = HashMap<String, Pair<java.io.File, String>>()
    for (f in dir.listFiles().orEmpty()) {
        val name = f.name.removeSuffix(".better")
        val id = runCatching { java.net.URLDecoder.decode(name.substringBeforeLast('.'), "UTF-8") }.getOrNull()
        val origin = name.substringAfterLast('.')
        val stored = id?.let(storedOrigin)
        val wanted = id != null && name != f.name && '.' in name && (stored == null || qualityRank(origin) > qualityRank(stored))
        if (wanted && qualityRank(origin) > qualityRank(keep[id]?.second)) { keep[id]?.first?.delete(); keep[id] = f to origin } else f.delete()
    }
    return keep
}
