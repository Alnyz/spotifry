package com.spotifry.music.data

import com.spotifry.music.BuildConfig
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
        const val SIMPMUSIC = "SimpMusic"
        const val NETEASE = "NetEase"
        const val DEEZER = "Deezer"
        /** Every source, best first: asked together; word-timed beats line-timed, then the earlier wins. LRCLIB is asked only when the rest miss. */
        val SOURCES = listOf(BETTER_LYRICS, LRC_RED, PORTATO, SIMPMUSIC, NETEASE, DEEZER, UNISON, LRCLIB)
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
        val asked = listOf<Pair<String, (Song) -> Lyrics?>>(Lyrics.BETTER_LYRICS to ::wordTimed, Lyrics.LRC_RED to ::lrcRed, Lyrics.PORTATO to ::portato,
            Lyrics.SIMPMUSIC to ::simpMusic, Lyrics.NETEASE to ::netease, Lyrics.DEEZER to ::deezer, Lyrics.UNISON to ::unison)
            .filter { it.first !in skip }.map { (_, ask) -> async { ask(song) } }
        val answers = asked.awaitAll().filterNotNull() // all run together, each capped at 4 seconds
        answers.firstOrNull { a -> a is Lyrics.Timed && a.lines.any { it.words.isNotEmpty() } } ?: answers.firstOrNull()
    }

    /** Time left of a source's 4 seconds, so a source that needs two or three requests still answers in 4 s in all. */
    private fun fetch(url: String, endAtMs: Long, post: String? = null, headers: Map<String, String> = emptyMap()): String? {
        val left = endAtMs - System.currentTimeMillis()
        if (left <= 0) return null
        val req = Request.Builder().url(url).header("User-Agent", UA).apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (post != null) post(post.toRequestBody("application/json".toMediaType()))
        }.build()
        return quick.newBuilder().callTimeout(left, java.util.concurrent.TimeUnit.MILLISECONDS).build().newCall(req).execute().use {
            if (it.code == 401 || it.code == 403) throw IOException("refused ${it.code}")
            if (!it.isSuccessful) null else it.body!!.string()
        }
    }

    /**
     * SimpMusic Lyrics (listener-contributed): asked by the song's YouTube video id, so there is no guessing which song
     * it is. Several entries can exist for one video; the one nearest in length counts, and only within 3 seconds.
     * Songs that are not from YouTube (this phone, JioSaavn) have no video id and are not asked.
     */
    private fun simpMusic(song: Song): Lyrics? = runCatching {
        if (!VIDEO_ID.matches(song.id)) return null
        val body = fetch("https://api-lyrics.simpmusic.org/v1/${song.id}", System.currentTimeMillis() + 4_000, headers = mapOf("User-Agent" to "SimpMusicLyrics/1.0", "Accept" to "application/json")) ?: return null
        val json = JSONObject(body)
        if (json.optString("type") != "success") return null
        simpMusicLines(json.optJSONArray("data") ?: return null, song.durationSec)?.let { Lyrics.Timed(it, Lyrics.SIMPMUSIC) }
    }.getOrNull()

    /**
     * NetEase Cloud Music's word-timed lyrics (yrc). Unofficial, no account: a free-text search, then the lyric of the
     * hit that is this recording ([neteaseId]). Any error or timeout is a miss.
     */
    private fun netease(song: Song): Lyrics? = runCatching {
        val end = System.currentTimeMillis() + 4_000
        val search = "https://music.163.com/api/search/get".toHttpUrl().newBuilder().addQueryParameter("s", "${song.title} ${song.artist}")
            .addQueryParameter("type", "1").addQueryParameter("limit", "25").build()
        val hits = JSONObject(fetch(search.toString(), end) ?: return null).optJSONObject("result")?.optJSONArray("songs") ?: return null
        val id = neteaseId(hits, song.title, song.artist, song.durationSec) ?: return null
        val body = fetch("https://music.163.com/api/song/lyric?id=$id&lv=1&yv=1&tv=-1", end) ?: return null
        parseYrc(JSONObject(body).optJSONObject("yrc")?.optString("lyric").orEmpty()).takeIf { it.isNotEmpty() }?.let { Lyrics.Timed(it, Lyrics.NETEASE) }
    }.getOrNull()

    // Deezer's guest pass: handed out without an account, good for about 6 minutes; kept until 30 s before it ends.
    private var deezerPass: Pair<String, Long>? = null

    @Synchronized private fun deezerJwt(end: Long): String? {
        deezerPass?.takeIf { System.currentTimeMillis() < it.second - 30_000 }?.let { return it.first }
        val jwt = JSONObject(fetch("https://auth.deezer.com/login/anonymous?jo=p&rto=c", end) ?: return null).optString("jwt").ifEmpty { return null }
        val exp = JSONObject(String(java.util.Base64.getUrlDecoder().decode(jwt.split('.')[1]))).optLong("exp") * 1000
        deezerPass = jwt to exp
        return jwt
    }

    /**
     * Deezer's word-timed lyrics. Unofficial: its public search finds the song ([deezerId]), its own lyrics interface
     * answers with a guest pass. A refused or stale pass is forgotten so the next song logs in again; any error is a miss.
     */
    private fun deezer(song: Song): Lyrics? = runCatching {
        val end = System.currentTimeMillis() + 4_000
        val search = "https://api.deezer.com/search".toHttpUrl().newBuilder().addQueryParameter("q", "${song.title} ${song.artist}").addQueryParameter("limit", "10").build()
        val hits = JSONObject(fetch(search.toString(), end) ?: return null).optJSONArray("data") ?: return null
        val id = deezerId(hits, song.title, song.artist, song.durationSec) ?: return null
        val query = JSONObject().put("query", "query { track(trackId: \"$id\") { lyrics { synchronizedWordByWordLines { start end words { start end word } } } } }").toString()
        val answer = try {
            fetch("https://pipe.deezer.com/api", end, query, mapOf("Authorization" to "Bearer ${deezerJwt(end) ?: return null}"))
        } catch (e: IOException) { deezerPass = null; throw e }
        val json = JSONObject(answer ?: return null)
        if (json.has("errors")) { deezerPass = null; return null }
        val lines = json.optJSONObject("data")?.optJSONObject("track")?.optJSONObject("lyrics")?.optJSONArray("synchronizedWordByWordLines") ?: return null
        deezerLines(lines, song.durationSec).takeIf { it.isNotEmpty() }?.let { Lyrics.Timed(it, Lyrics.DEEZER) }
    }.getOrNull()

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
            try { return block() } catch (e: IOException) { android.util.Log.w("Lyrics", "try ${n + 1} failed: ${e.message}"); delay(1_000L * (n + 1)) }
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

        /** Says who is asking, so a lyrics site's owner can reach us instead of blocking us. */
        private const val UA = "Spotifry/" + BuildConfig.VERSION_NAME + " (+https://github.com/Alnyz/spotifry)"

        /**
         * The ISRC (recording code) of the hit that is this recording, or null if none is sure to be. A miss just means
         * the next source is asked; the wrong words in time with the right song are worse, so every test must pass:
         * same title outside its brackets, same words naming another version (live, remix…), a shared artist, and a
         * length within 3 seconds.
         */
        fun bestHit(hits: JSONArray, title: String, artist: String, durationSec: Long): String? {
            return (0 until hits.length()).map { hits.getJSONObject(it) }.filter { h ->
                val name = h.optString("title").ifEmpty { return@filter false }
                Regex("[A-Z]{2}[A-Z0-9]{3}\\d{7}").matches(h.optString("isrc")) &&
                    sameRecording(name, h.optString("artist"), h.optDouble("duration").takeIf { h.has("duration") }, title, artist, durationSec)
            }.minByOrNull { if (it.has("duration")) abs(it.optDouble("duration") - durationSec) else 0.0 }?.getString("isrc")
        }

        /** The one test every lyrics source's hit must pass: same title outside its brackets, same version words, a shared artist, length within 3 seconds. */
        fun sameRecording(hitTitle: String, hitArtists: String, hitSec: Double?, title: String, artist: String, durationSec: Long): Boolean {
            val artists = artistsOf(artist)
            return coreOf(hitTitle) == coreOf(title) && versionOf(hitTitle) == versionOf(title) &&
                (artists.isEmpty() || artistsOf(hitArtists).any { it in artists }) &&
                (hitSec == null || durationSec <= 0 || abs(hitSec - durationSec) <= 3)
        }

        /** The id of the NetEase search hit that is this recording (nearest in length), or null. Hits give "name", "artists", "duration" in ms. */
        fun neteaseId(hits: JSONArray, title: String, artist: String, durationSec: Long): Long? =
            (0 until hits.length()).map { hits.getJSONObject(it) }.filter { h ->
                val names = h.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString(",") { a.getJSONObject(it).optString("name") } }.orEmpty()
                sameRecording(h.optString("name"), names, h.optDouble("duration").takeIf { h.has("duration") }?.div(1000), title, artist, durationSec)
            }.minByOrNull { abs(it.optDouble("duration") / 1000 - durationSec) }?.optLong("id")?.takeIf { it > 0 }

        /** The id of the Deezer search hit that is this recording (nearest in length), or null. Hits give "title", "title_short", "artist.name", "duration" in seconds. */
        fun deezerId(hits: JSONArray, title: String, artist: String, durationSec: Long): Long? =
            (0 until hits.length()).map { hits.getJSONObject(it) }.filter { h ->
                val by = h.optJSONObject("artist")?.optString("name").orEmpty()
                val sec = h.optDouble("duration").takeIf { h.has("duration") }
                sameRecording(h.optString("title"), by, sec, title, artist, durationSec) || sameRecording(h.optString("title_short"), by, sec, title, artist, durationSec)
            }.minByOrNull { abs(it.optDouble("duration") - durationSec) }?.optLong("id")?.takeIf { it > 0 }

        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        /** The word-timed lines of the SimpMusic entry nearest in length (within 3 s), or null when it has none. */
        fun simpMusicLines(entries: JSONArray, durationSec: Long): List<LyricLine>? =
            (0 until entries.length()).map { entries.getJSONObject(it) }
                .filter { durationSec <= 0 || !it.has("durationSeconds") || abs(it.optDouble("durationSeconds") - durationSec) <= 3 }
                .sortedBy { abs(it.optDouble("durationSeconds") - durationSec) }
                .firstNotNullOfOrNull { e -> parseRichSync(e.optString("richSyncLyrics")).takeIf { it.isNotEmpty() } }

        /**
         * SimpMusic's word format, one line each: "[00:27.39] <00:27.39>I <00:27.54>been <00:27.74>tryna <00:28.07>call".
         * A word ends where the next begins (the last, at the next line). Tags like {agent:v1} and {bg} are left out.
         */
        fun parseRichSync(raw: String): List<LyricLine> {
            val timed = raw.lines().mapNotNull { src ->
                val m = Regex("""^\[(\d+):(\d+(?:\.\d+)?)]\s*(.*)$""").find(src.trim()) ?: return@mapNotNull null
                val line = m.groupValues[1].toLong() * 60_000 + (m.groupValues[2].toDouble() * 1000).toLong()
                val words = Regex("""<(\d+):(\d+(?:\.\d+)?)>([^<]*)""").findAll(m.groupValues[3].replace(Regex("""\{[^}]*}"""), "")).mapNotNull { w ->
                    val text = w.groupValues[3].ifBlank { return@mapNotNull null }
                    (w.groupValues[1].toLong() * 60_000 + (w.groupValues[2].toDouble() * 1000).toLong()) to text
                }.toList()
                if (words.isEmpty()) null else line to words
            }.sortedBy { it.first }
            return timed.mapIndexed { i, (line, words) ->
                val ends = words.drop(1).map { it.first } + (timed.getOrNull(i + 1)?.first ?: (words.last().first + 1_000))
                val ws = words.mapIndexed { n, (at, text) -> LyricWord(at, maxOf(at, ends[n]), text) }
                LyricLine(minOf(line, ws.first().startMs), ws.joinToString("") { it.text }.trim(), ws)
            }
        }

        /**
         * NetEase's word format, one line each: "[lineStartMs,lengthMs](wordStartMs,wordLengthMs,0)word (..." Credit lines
         * at the top ("作词 : …", "Lyrics by: …") are dropped; lines that are not in this format (metadata) are skipped.
         */
        fun parseYrc(raw: String): List<LyricLine> {
            val lines = raw.lines().mapNotNull { src ->
                val m = Regex("""^\[(\d+),(\d+)](.*)$""").find(src.trim()) ?: return@mapNotNull null
                val words = Regex("""\((\d+),(\d+),\d+\)([^()]*)""").findAll(m.groupValues[3]).map { w ->
                    val at = w.groupValues[1].toLong()
                    LyricWord(at, at + w.groupValues[2].toLong(), w.groupValues[3])
                }.filter { it.text.isNotBlank() }.toList()
                val text = words.joinToString("") { it.text }.trim()
                if (text.isEmpty()) null else LyricLine(minOf(m.groupValues[1].toLong(), words.first().startMs), text, words)
            }.sortedBy { it.atMs }
            val credits = lines.takeWhile { ":" in it.text || "：" in it.text }.size
            return lines.drop(credits)
        }

        /** Deezer's lines of bare words: the words keep their timing and get their spaces back. A song whose lyrics run well past its length is another version, so it is dropped. */
        fun deezerLines(lines: JSONArray, durationSec: Long): List<LyricLine> {
            val out = (0 until lines.length()).mapNotNull { i ->
                val ws = lines.getJSONObject(i).optJSONArray("words") ?: return@mapNotNull null
                val raw = (0 until ws.length()).map { ws.getJSONObject(it) }.filter { it.optString("word").isNotBlank() }
                if (raw.isEmpty()) return@mapNotNull null
                val words = raw.mapIndexed { n, w ->
                    val text = w.optString("word").trim()
                    LyricWord(w.optLong("start"), w.optLong("end"), if (n < raw.size - 1 && spaceBetween(text, raw[n + 1].optString("word").trim())) "$text " else text)
                }
                LyricLine(words.first().startMs, words.joinToString("") { it.text }, words)
            }.sortedBy { it.atMs }
            val last = out.maxOfOrNull { l -> l.words.maxOf { it.endMs } } ?: return emptyList()
            return if (durationSec > 0 && last > durationSec * 1000 + 3_000) emptyList() else out
        }

        private val NO_SPACE_SCRIPTS = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.THAI)

        /** A space between two bare words, except before closing punctuation and between letters of scripts written without spaces. */
        fun spaceBetween(prev: String, next: String): Boolean {
            if (prev.isEmpty() || next.isEmpty()) return false
            if (next.first() in ",.!?;:)]}…%。，！？、；：）」』") return false
            if (prev.last() in "([{「『") return false
            return !(Character.UnicodeScript.of(prev.last().code) in NO_SPACE_SCRIPTS && Character.UnicodeScript.of(next.first().code) in NO_SPACE_SCRIPTS)
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
