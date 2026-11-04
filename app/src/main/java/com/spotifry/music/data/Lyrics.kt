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

/** One word as it is sung; [text] keeps the space after it, if any, so the words join back into the line. */
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

/** A line and when it starts; [words] has each word's timing when the lyrics site knows it, else empty. */
data class LyricLine(val atMs: Long, val text: String, val words: List<LyricWord> = emptyList())

sealed interface Lyrics {
    /** Lines with the time each is sung, so the one being sung can be shown. */
    data class Timed(val lines: List<LyricLine>, val from: String = LRCLIB) : Lyrics
    data class Plain(val lines: List<String>) : Lyrics

    companion object {
        const val LRCLIB = "LRCLIB"
        const val BETTER_LYRICS = "BetterLyrics"
    }
}

/**
 * Lyrics timed word by word from BetterLyrics (lyrics-api.boidu.dev, Apple Music's timings) when it has the song,
 * else from LRCLIB (lrclib.net), timed by line. Both free, no account; both get the song's title, artist and length.
 * Null means neither has lyrics for the song; an exception means LRCLIB couldn't be asked (offline, busy).
 */
class LyricsSource(private val client: OkHttpClient) {
    // ponytail: kept for this run of the app only; a disk copy would make lyrics work offline
    private val known = HashMap<String, Lyrics?>()

    suspend fun get(song: Song): Lyrics? {
        if (song.id in known) return known[song.id]
        val found = withContext(Dispatchers.IO) { wordTimed(song) ?: tries { exact(song) } ?: tries { closest(song) } }
        known[song.id] = found
        return found
    }

    /**
     * BetterLyrics only answers for songs someone has looked up before (others need a key it doesn't hand out),
     * so any no, error or timeout just means: ask LRCLIB.
     */
    private fun wordTimed(song: Song): Lyrics? = runCatching {
        val url = "https://lyrics-api.boidu.dev/getLyrics".toHttpUrl().newBuilder()
            .addQueryParameter("s", song.title).addQueryParameter("a", song.artist).addQueryParameter("d", song.durationSec.toString()).build()
        client.newCall(Request.Builder().url(url).header("User-Agent", "Spotifry (personal music player)").build()).execute().use {
            if (!it.isSuccessful) return null
            parseTtml(JSONObject(it.body!!.string()).optString("ttml")).takeIf { l -> l.isNotEmpty() }?.let { l -> Lyrics.Timed(l, Lyrics.BETTER_LYRICS) }
        }
    }.getOrNull()

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

        /**
         * Apple Music's lyrics format (TTML): a <p> per line with its start, and inside it a <span> per word (or per
         * syllable: spans with no space between are one word, "good" + "bye"). Background singing is left out.
         */
        fun parseTtml(ttml: String): List<LyricLine> {
            val body = ttml.substringAfter("<body", "").ifEmpty { return emptyList() }
                .replace(Regex("""<span[^>]*ttm:role="x-(bg|translation|roman)[^"]*"[^>]*>(?:\s*<span[^>]*>[^<]*</span>)*[^<]*</span>"""), "")
            return Regex("""<p\b([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).findAll(body).mapNotNull { p ->
                val at = attr(p.groupValues[1], "begin")?.let(::clock) ?: return@mapNotNull null
                val inner = p.groupValues[2]
                val words = Regex("""<span\b([^>]*)>([^<]*)</span>(\s*)""").findAll(inner).mapNotNull { s ->
                    val start = attr(s.groupValues[1], "begin")?.let(::clock) ?: return@mapNotNull null
                    val end = attr(s.groupValues[1], "end")?.let(::clock) ?: start
                    LyricWord(start, end, unescape(s.groupValues[2]) + if (s.groupValues[3].isNotEmpty()) " " else "")
                }.toList()
                val text = (if (words.isNotEmpty()) words.joinToString("") { it.text } else unescape(inner.replace(Regex("<[^>]+>"), ""))).trim()
                if (text.isEmpty()) null else LyricLine(at, text, words)
            }.sortedBy { it.atMs }.toList()
        }

        private fun attr(attrs: String, name: String) = Regex("""\b$name="([^"]*)"""").find(attrs)?.groupValues?.get(1)

        /** "1:02.345", "62.345" or "1:01:02.3" to milliseconds. */
        fun clock(t: String): Long? = runCatching {
            t.split(':').fold(0.0) { acc, part -> acc * 60 + part.toDouble() }.times(1000).toLong()
        }.getOrNull()

        private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")

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
