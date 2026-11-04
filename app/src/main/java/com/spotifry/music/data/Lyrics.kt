package com.spotifry.music.data

import com.spotifry.music.data.source.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs

data class LyricLine(val atMs: Long, val text: String)

sealed interface Lyrics {
    /** Lines with the time each is sung, so the one being sung can be shown. */
    data class Timed(val lines: List<LyricLine>) : Lyrics
    data class Plain(val lines: List<String>) : Lyrics
}

/**
 * Lyrics from LRCLIB (lrclib.net): free, no account. Sends the song's title, artist and length.
 * Null means the site has no lyrics for the song; an exception means it couldn't be asked (offline, busy).
 */
class LyricsSource(private val client: OkHttpClient) {
    // ponytail: kept for this run of the app only; a disk copy would make lyrics work offline
    private val known = HashMap<String, Lyrics?>()

    suspend fun get(song: Song): Lyrics? {
        if (song.id in known) return known[song.id]
        val found = withContext(Dispatchers.IO) { tries { exact(song) } ?: tries { closest(song) } }
        known[song.id] = found
        return found
    }

    /** The site is often briefly "busy" (503): three tries, a second or two apart, before giving up. */
    private suspend fun <T> tries(block: () -> T): T {
        repeat(2) { n ->
            try { return block() } catch (e: IOException) { android.util.Log.w("Lyrics", "try ${n + 1} failed: ${e.message}"); delay(1_000L * (n + 1)) }
        }
        return block()
    }

    private fun exact(song: Song): Lyrics? {
        val url = BASE.toHttpUrl().newBuilder().addPathSegments("api/get")
            .addQueryParameter("track_name", song.title).addQueryParameter("artist_name", song.artist)
            .addQueryParameter("duration", song.durationSec.toString()).build()
        val body = call(url.toString()) ?: return null
        return fromEntry(JSONObject(body))
    }

    /** The search finds songs whose title or artist is written a bit differently; the length keeps it honest. */
    private fun closest(song: Song): Lyrics? {
        val url = BASE.toHttpUrl().newBuilder().addPathSegments("api/search")
            .addQueryParameter("track_name", song.title).addQueryParameter("artist_name", song.artist).build()
        val body = call(url.toString()) ?: return null
        return pickClosest(JSONArray(body), song.durationSec)
    }

    private fun call(url: String): String? =
        client.newCall(Request.Builder().url(url).header("User-Agent", "Spotifry (personal music player)").build()).execute().use {
            if (it.code == 404) return null
            if (!it.isSuccessful) throw IOException("lyrics site said ${it.code}")
            it.body!!.string()
        }

    companion object {
        private const val BASE = "https://lrclib.net/"

        fun pickClosest(results: JSONArray, durationSec: Long): Lyrics? =
            (0 until results.length()).map { results.getJSONObject(it) }
                .filter { abs(it.optDouble("duration", 0.0) - durationSec) <= 5 }
                .sortedByDescending { !it.isNull("syncedLyrics") }
                .firstNotNullOfOrNull(::fromEntry)

        fun fromEntry(o: JSONObject): Lyrics? {
            if (o.optBoolean("instrumental")) return null
            val synced = if (o.isNull("syncedLyrics")) "" else o.optString("syncedLyrics")
            parseLrc(synced).takeIf { it.isNotEmpty() }?.let { return Lyrics.Timed(it) }
            val plain = if (o.isNull("plainLyrics")) "" else o.optString("plainLyrics")
            return plain.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { Lyrics.Plain(it) }
        }

        /** "[01:23.45] words" lines; lines with no words are dropped. */
        fun parseLrc(text: String): List<LyricLine> = text.lines().mapNotNull { line ->
            val m = Regex("""^\[(\d+):(\d+(?:\.\d+)?)]\s*(.*)$""").find(line.trim()) ?: return@mapNotNull null
            val words = m.groupValues[3].trim().ifEmpty { return@mapNotNull null }
            LyricLine(m.groupValues[1].toLong() * 60_000 + (m.groupValues[2].toDouble() * 1000).toLong(), words)
        }.sortedBy { it.atMs }
    }
}

/** The line being sung at [positionMs]: the last one that has started. -1 before the first. */
fun List<LyricLine>.indexAt(positionMs: Long): Int = indexOfLast { it.atMs <= positionMs }
