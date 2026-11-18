package com.spotifry.music.data.source

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** A song in Monochrome's search results. [durationMs] is as Tidal gives it. */
data class MonochromeResult(val id: String, val title: String, val artists: List<String>, val durationMs: Long, val playable: Boolean)

/**
 * The song as lossless FLAC from Monochrome's track server (the one Mochy Music also uses), or null without a sure match.
 * Unofficial and not ours: the server plays through someone else's Tidal account and can go away any day, so a failure
 * is remembered for a few minutes instead of costing every song a wait.
 */
class MonochromeSource(
    client: OkHttpClient,
    private val base: String = "https://tracks.monochrome.st",
    private val now: () -> Long = System::currentTimeMillis,
    /** Says how each lookup went, for a log. */
    private val log: (String) -> Unit = {},
) {
    @Volatile private var downUntil = 0L
    /** The server often takes over 10 s to answer a search, then answers in 1 s the next time: it gets 20 s, and a second try. */
    private val client = client.newBuilder().readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()

    /** What was found (or not) for a song, so a tap, the lookups ahead and the upgrade check do not each ask the server again. */
    private class Seen(val at: Long, val stream: ResolvedStream?)
    private val seen = ConcurrentHashMap<String, Seen>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    /** The server refuses when asked too much at once, so only a couple of lookups run together. */
    private val gate = Semaphore(2)

    suspend fun find(song: Song): ResolvedStream? {
        recall(song)?.let { if (it.stream == null) log("${song.id}: not found a moment ago, not asked again yet"); return it.stream }
        if (now() < downUntil) return null.also { log("${song.id}: not asked, the server is left alone for ${(downUntil - now()) / 1000} s more") }
        // the same song asked by several callers at once is looked up once; the others read what it found
        return locks.computeIfAbsent(song.id) { Mutex() }.withLock {
            recall(song)?.let { return@withLock it.stream }
            if (now() < downUntil) return@withLock null
            gate.withPermit { lookup(song) }
        }
    }

    private fun recall(song: Song): Seen? = seen[song.id]?.takeIf {
        val s = it.stream
        if (s != null) s.expiresAtMs - LINK_SPARE_MS > now() else now() - it.at < NOT_FOUND_MS
    }

    private suspend fun lookup(song: Song): ResolvedStream? {
        try {
            val search = base.toHttpUrl().newBuilder().addPathSegments("search/tracks")
                .addQueryParameter("q", "${cleanTitle(song.title)} ${song.artist.removeSuffix(" - Topic")}").addQueryParameter("limit", "8").build()
            val body = searchOnce(search.toString()) ?: searchOnce(search.toString(), last = true)!!
            val results = parseMonochromeResults(body)
            val hit = results.firstOrNull { it.playable && isSameRecording(song, it.title, it.artists, "", it.durationMs / 1000) }
                ?: return null.also {
                    seen[song.id] = Seen(now(), null)
                    log("no match for ${song.id} \"${song.title}\" ${song.durationSec}s among ${results.joinToString { "\"${it.title}\" ${it.durationMs / 1000}s" }}")
                }
            val link = base.toHttpUrl().newBuilder().addPathSegments("track").addPathSegment(hit.id).build().toString()
            // the first bytes say whether it really is FLAC; the range's total says how big, which gives the bitrate for the label
            val total = client.newCall(request(link).header("Range", "bytes=0-3").build()).read { r ->
                if (r.code != 206 && r.code != 200) return@read null
                val isFlac = r.body!!.source().readUtf8(4) == "fLaC"
                if (isFlac) r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: 0L else null
            } ?: return null.also { seen[song.id] = Seen(now(), null); log("${song.id}: the found copy isn't FLAC") }
            val kbps = if (total > 0 && hit.durationMs > 0) (total * 8 / hit.durationMs).toInt() else 0
            return ResolvedStream(link, "audio/flac", kbps, now() + LINK_LIFE_MS, fromMonochrome = true).also { seen[song.id] = Seen(now(), it) }
        } catch (e: IOException) {
            // asked too often (429): a short pause. Any other failure: the server is left alone for a few minutes
            downUntil = now() + if (e.message?.contains("429") == true) RATE_LIMITED_MS else DOWN_MS
            log("${song.id}: server left alone for ${(downUntil - now()) / 1000} s after $e")
            return null
        }
    }

    /** One search; a slow or failed answer is null so it is asked once more, except a refusal for asking too often and the [last] try. */
    private suspend fun searchOnce(url: String, last: Boolean = false): String? = try {
        client.newCall(request(url).build()).read { r -> if (r.isSuccessful) r.body!!.string() else throw IOException("HTTP ${r.code}") }
    } catch (e: IOException) {
        if (last || e.message?.contains("429") == true) throw e
        log("search failed ($e), asking once more"); null
    }

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", OkHttpDownloader.USER_AGENT)

    private companion object {
        const val LINK_LIFE_MS = 60 * 60 * 1000L
        const val DOWN_MS = 5 * 60 * 1000L
        const val RATE_LIMITED_MS = 60 * 1000L
        const val NOT_FOUND_MS = 10 * 60 * 1000L
        /** A found link is used again until this long before it expires. */
        const val LINK_SPARE_MS = 5 * 60 * 1000L
    }
}

fun parseMonochromeResults(json: String): List<MonochromeResult> {
    val tracks = JSONObject(json).optJSONArray("tracks") ?: return emptyList()
    return List(tracks.length()) { tracks.getJSONObject(it) }.mapNotNull { t ->
        val names = t.optJSONArray("artistNames")
        MonochromeResult(
            id = t.optString("trackId").ifEmpty { return@mapNotNull null },
            title = t.optString("title"),
            artists = List(names?.length() ?: 0) { names!!.getString(it) },
            durationMs = t.optLong("duration"),
            playable = t.optBoolean("playable", true),
        )
    }
}
