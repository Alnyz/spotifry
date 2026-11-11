package com.spotifry.music.data

import com.spotifry.music.data.source.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        const val LRC_RED = "lrc.red"
        const val UNISON = "Unison"
        const val PORTATO = "QQ Music"
        /** Every source, best first: asked together; word-timed beats line-timed, then the earlier wins. LRCLIB is asked only when the rest miss. */
        val SOURCES = listOf(BETTER_LYRICS, LRC_RED, PORTATO, UNISON, LRCLIB)
    }
}

/**
 * Lyrics timed word by word from BetterLyrics (lyrics-api.boidu.dev) or lrc.red, both Apple Music's timings, asked at
 * the same time; a word-timed answer beats a line-timed one, then the earlier in [Lyrics.SOURCES] wins. LRCLIB (lrclib.net, timed by line) is asked only if none has the song.
 * All free, no account; all get the song's title, artist and length. [off] names the sources the listener turned off.
 * Null means no source has lyrics for the song; an exception means LRCLIB couldn't be asked (offline, busy).
 */
class LyricsSource(private val client: OkHttpClient, private val off: () -> Set<String> = { emptySet() }) {
    // ponytail: kept for this run of the app only; a disk copy would make lyrics work offline
    private val known = HashMap<String, Lyrics?>()
    /** A word-timed source that hasn't answered in 4 seconds is a miss, so a dead site can't hold the lyrics up. */
    private val quick = client.newBuilder().callTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build()

    suspend fun get(song: Song): Lyrics? {
        val skip = off()
        val key = song.id + skip.sorted()
        if (key in known) return known[key]
        val found = withContext(Dispatchers.IO) {
            fromSources(song, skip) ?: if (Lyrics.LRCLIB in skip) null else lrclib(song)
        }
        known[key] = found
        return found
    }

    /** Word-timed beats line-timed whatever the source; within each, the better source (earlier in [Lyrics.SOURCES]) wins. */
    private suspend fun fromSources(song: Song, skip: Set<String>): Lyrics? = coroutineScope {
        val asked = listOf<Pair<String, (Song) -> Lyrics?>>(Lyrics.BETTER_LYRICS to ::wordTimed, Lyrics.LRC_RED to ::lrcRed, Lyrics.PORTATO to ::portato, Lyrics.UNISON to ::unison)
            .filter { it.first !in skip }.map { (_, ask) -> async { ask(song) } }
        val answers = asked.awaitAll().filterNotNull() // all run together, each capped at 4 seconds
        answers.firstOrNull { a -> a is Lyrics.Timed && a.lines.any { it.words.isNotEmpty() } } ?: answers.firstOrNull()
    }

    /** QQ Music's karaoke lyrics through BetterLyrics' Portato endpoint: a big catalogue, timed word by word. */
    private fun portato(song: Song): Lyrics? = runCatching {
        val url = "https://lyrics-api.boidu.dev/qq/getLyrics".toHttpUrl().newBuilder()
            .addQueryParameter("s", song.title).addQueryParameter("a", song.artist).addQueryParameter("d", song.durationSec.toString()).build()
        val raw = quick.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use {
            if (!it.isSuccessful) return null
            JSONObject(it.body!!.string()).optString("lyrics")
        }
        parseQrc(raw).takeIf { it.isNotEmpty() }?.let { Lyrics.Timed(it, Lyrics.PORTATO) }
    }.getOrNull()

    /** Unison is listener-contributed: it answers with TTML (word timed), LRC (line timed) or plain text, which is left to LRCLIB. */
    private fun unison(song: Song): Lyrics? = runCatching {
        val url = "https://unison.boidu.dev/lyrics".toHttpUrl().newBuilder()
            .addQueryParameter("song", song.title).addQueryParameter("artist", song.artist).addQueryParameter("duration", song.durationSec.toString()).build()
        val data = quick.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use {
            if (!it.isSuccessful) return null
            JSONObject(it.body!!.string()).takeIf { o -> o.optBoolean("success") }?.optJSONObject("data") ?: return null
        }
        unisonLines(data.optString("lyrics"), data.optString("format"), data.optString("syncType"))?.let { Lyrics.Timed(it, Lyrics.UNISON) }
    }.getOrNull()

    /** lrc.red's search is free text, so a hit counts only when [bestHit] is sure it is this recording. */
    private fun lrcRed(song: Song): Lyrics? = runCatching {
        val url = "https://lrc.red/search.json".toHttpUrl().newBuilder().addQueryParameter("q", "${song.title} ${song.artist}").build()
        val hits = quick.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use {
            if (!it.isSuccessful) return null
            JSONObject(it.body!!.string()).optJSONArray("hits") ?: return null
        }
        val isrc = bestHit(hits, song.title, song.artist, song.durationSec) ?: return null
        quick.newCall(Request.Builder().url("https://lrc.red/s/$isrc.ttml").header("User-Agent", UA).build()).execute().use {
            if (!it.isSuccessful) return null
            parseTtml(it.body!!.string()).takeIf { l -> l.isNotEmpty() }?.let { l -> Lyrics.Timed(l, Lyrics.LRC_RED) }
        }
    }.getOrNull()

    /**
     * BetterLyrics only answers for songs someone has looked up before (others need a key it doesn't hand out),
     * so any no, error or timeout just means: ask LRCLIB.
     */
    private fun wordTimed(song: Song): Lyrics? = runCatching {
        val url = "https://lyrics-api.boidu.dev/getLyrics".toHttpUrl().newBuilder()
            .addQueryParameter("s", song.title).addQueryParameter("a", song.artist).addQueryParameter("d", song.durationSec.toString()).build()
        quick.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use {
            if (!it.isSuccessful) return null
            parseTtml(JSONObject(it.body!!.string()).optString("ttml")).takeIf { l -> l.isNotEmpty() }?.let { l -> Lyrics.Timed(l, Lyrics.BETTER_LYRICS) }
        }
    }.getOrNull()

    /** The site is often briefly "busy" (503): three tries, a second or two apart, before giving up. */
    private suspend fun <T> tries(block: () -> T): T {
        repeat(2) { n ->
            try { return block() } catch (e: IOException) { Log.w("Lyrics", "try ${n + 1} failed: ${e.message}"); delay(1_000L * (n + 1)) }
        }
        return block()
    }

    /** An exact record marked instrumental is the answer (no lyrics); only a missing record or an empty one goes on to the fuzzy search. */
    private suspend fun lrclib(song: Song): Lyrics? {
        val record = tries { exact(song) }
        if (record?.optBoolean("instrumental") == true) return null
        return record?.let(::fromEntry) ?: tries { closest(song) }
    }

    private fun exact(song: Song): JSONObject? {
        val url = BASE.toHttpUrl().newBuilder().addPathSegments("api/get")
            .addQueryParameter("track_name", song.title).addQueryParameter("artist_name", song.artist)
            .addQueryParameter("duration", song.durationSec.toString()).build()
        val body = call(url.toString()) ?: return null
        return JSONObject(body)
    }

    /** The search finds songs whose title or artist is written a bit differently; the length keeps it honest. */
    private fun closest(song: Song): Lyrics? {
        val url = BASE.toHttpUrl().newBuilder().addPathSegments("api/search")
            .addQueryParameter("track_name", song.title).addQueryParameter("artist_name", song.artist).build()
        val body = call(url.toString()) ?: return null
        return pickClosest(JSONArray(body), song.durationSec)
    }

    private fun call(url: String): String? =
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use {
            if (it.code == 404) return null
            if (!it.isSuccessful) throw IOException("lyrics site said ${it.code}")
            it.body!!.string()
        }

    companion object {
        private const val BASE = "https://lrclib.net/"
        /**
         * QQ Music's karaoke format, inside an XML wrapper: "[lineStartMs,lengthMs]word (startMs,lengthMs)word (...)".
         * Each word keeps the space after it. Credit lines ("Composed by：…") and the title line are dropped. If the
         * entry has plain "[mm:ss.xx]" lines instead, those are used, timed by line.
         */
        fun parseQrc(raw: String): List<LyricLine> {
            val body = Regex("""LyricContent="([^"]*)"""").find(raw)?.groupValues?.get(1)?.let(::unescapeXml) ?: raw
            val lines = body.lines().mapNotNull { src ->
                val m = Regex("""^\[(\d+),(\d+)](.*)$""").find(src.trim()) ?: return@mapNotNull null
                val words = Regex("""([^()]*)\((\d+),(\d+)\)""").findAll(m.groupValues[3]).map { w ->
                    val at = w.groupValues[2].toLong()
                    LyricWord(at, at + w.groupValues[3].toLong(), w.groupValues[1])
                }.filter { it.text.isNotEmpty() }.toList()
                val text = words.joinToString("") { it.text }.trim()
                if (text.isEmpty() || "：" in text || (m.groupValues[1] == "0" && " - " in text)) null
                else LyricLine(minOf(m.groupValues[1].toLong(), words.first().startMs), text, words)
            }.sortedBy { it.atMs }
            return lines.ifEmpty { parseLrc(body) }
        }

        private fun unescapeXml(s: String) = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

        /** The timed lines of a Unison entry, or null when it is empty or plain text. */
        fun unisonLines(text: String, format: String, syncType: String): List<LyricLine>? {
            if (text.isBlank() || syncType.equals("plain", true)) return null
            return (if (format.equals("ttml", true)) parseTtml(text) else parseLrc(text)).takeIf { it.isNotEmpty() }
        }

        private const val UA = "Spotifry (personal music player)"

        /**
         * The ISRC (recording code) of the hit that is this recording, or null if none is sure to be. A miss just means
         * the next source is asked; the wrong words in time with the right song are worse, so every test must pass:
         * same title outside its brackets, same words naming another version (live, remix…), a shared artist, and a
         * length within 3 seconds.
         */
        fun bestHit(hits: JSONArray, title: String, artist: String, durationSec: Long): String? {
            val core = coreOf(title); val version = versionOf(title); val artists = artistsOf(artist)
            return (0 until hits.length()).map { hits.getJSONObject(it) }.filter { h ->
                val name = h.optString("title").ifEmpty { return@filter false }
                Regex("[A-Z]{2}[A-Z0-9]{3}\\d{7}").matches(h.optString("isrc")) &&
                    coreOf(name) == core && versionOf(name) == version &&
                    (artists.isEmpty() || artistsOf(h.optString("artist")).any { it in artists }) &&
                    (!h.has("duration") || durationSec <= 0 || abs(h.optDouble("duration") - durationSec) <= 3)
            }.minByOrNull { if (it.has("duration")) abs(it.optDouble("duration") - durationSec) else 0.0 }?.getString("isrc")
        }

        private val BRACKETED = Regex("""[(\[][^)\]]*[)\]]""")
        private val VERSION_WORDS = setOf("live", "remix", "remixed", "mix", "acoustic", "unplugged", "instrumental", "karaoke", "cappella",
            "acapella", "demo", "edit", "version", "cover", "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "extended")

        /** Lower case, accents off, only letters and digits. */
        private fun plain(v: String) = java.text.Normalizer.normalize(v, java.text.Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
            .lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").replace(Regex("""\s+"""), " ").trim()

        /** "Song (Live) - 2011 Remaster" gives "song". */
        private fun coreOf(t: String) = plain(t.replace(BRACKETED, " ").substringBefore(" - ")).ifEmpty { plain(t) }
        private fun versionOf(t: String) = plain(BRACKETED.findAll(t).joinToString(" ") { it.value } + " " + t.substringAfter(" - ", ""))
            .split(' ').filter { it in VERSION_WORDS }.toSet()
        private fun artistsOf(a: String) = a.split(Regex("""\s*(?:,|&|;|/|\s+and\s+|\s+x\s+|\s+with\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*""", RegexOption.IGNORE_CASE))
            .map(::plain).filter { it.isNotEmpty() }.toSet()

        fun pickClosest(results: JSONArray, durationSec: Long): Lyrics? =
            (0 until results.length()).map { results.getJSONObject(it) }
                .filter { abs(it.optDouble("duration", 0.0) - durationSec) <= 5 }
                .sortedByDescending { !it.isNull("syncedLyrics") }
                .firstNotNullOfOrNull { fromEntry(it)?.takeUnless(::isJunk) }

        /** Untimed text that is one line said over and over (6+ lines, 90%+ the same) is a bad upload; only trusted for an exact match. */
        private fun isJunk(l: Lyrics): Boolean {
            val lines = (l as? Lyrics.Plain)?.lines?.map { it.trim().lowercase() } ?: return false
            return lines.size >= 6 && lines.groupingBy { it }.eachCount().values.max() >= lines.size * 0.9
        }

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
