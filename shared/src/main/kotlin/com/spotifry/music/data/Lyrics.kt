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
        const val KUGOU = "KuGou"
        /** Every source, best first: asked together; word-timed beats line-timed, then the earlier wins. LRCLIB is asked only when the rest miss. */
        val SOURCES = listOf(KUGOU, BETTER_LYRICS, LRC_RED, PORTATO, SIMPMUSIC, NETEASE, DEEZER, UNISON, LRCLIB)
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
        // asked under the name a lyrics site would have filed the song, not YouTube's upload title and channel name
        val named = song.copy(title = titleForLyrics(song.title, song.artist), artist = artistForLyrics(song.artist))
        val found = withContext(Dispatchers.IO) {
            (fromSources(named, skip) ?: if (Lyrics.LRCLIB in skip) null else lrclib(named))?.cleaned()
        }
        known[key] = found
        return found
    }

    /** Word-timed beats line-timed whatever the source; within each, the better source (earlier in [Lyrics.SOURCES]) wins. */
    private suspend fun fromSources(song: Song, skip: Set<String>): Lyrics? = coroutineScope {
        val asked = listOf<Pair<String, (Song) -> Lyrics?>>(Lyrics.KUGOU to ::kugou, Lyrics.BETTER_LYRICS to ::wordTimed, Lyrics.LRC_RED to ::lrcRed, Lyrics.PORTATO to ::portato,
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

    /**
     * KuGou's karaoke lyrics (word by word, "KRC"), with its line-timed file as the fallback. KuGou's lyric search is
     * by "Artist - Title"; when that finds nothing, its song search gives the file hashes of this song, and the lyric
     * search is asked by hash. Only lyrics of the same title, an artist sharing a word and a length within 5 s count, and
     * lyrics whose own title names another edition (live, remix…) are passed over. Unofficial. Follows Prism's way of asking.
     */
    private fun kugou(song: Song): Lyrics? = runCatching {
        val end = System.currentTimeMillis() + 5_000
        fun url(base: String, vararg q: Pair<String, String>) =
            base.toHttpUrl().newBuilder().apply { q.forEach { (k, v) -> addQueryParameter(k, v) } }.build().toString()
        fun search(keyword: String, hash: String = ""): JSONArray? =
            fetch(url("https://lyrics.kugou.com/search", "ver" to "1", "man" to "yes", "client" to "pc", "keyword" to keyword,
                "duration" to (song.durationSec * 1000).toString(), "hash" to hash), end)
                ?.let { JSONObject(it).optJSONArray("candidates") }?.takeIf { it.length() > 0 }
        fun hashes() = fetch(url("https://songsearch.kugou.com/song_search_v2", "keyword" to "${song.artist} ${song.title}", "page" to "1", "pagesize" to "10", "platform" to "WebFilter"), end)
            ?.let { JSONObject(it).optJSONObject("data")?.optJSONArray("lists") }?.let { kugouHashes(it, song.title, song.artist, song.durationSec) }.orEmpty()
        val candidates = search("${song.artist} - ${song.title}") ?: hashes().firstNotNullOfOrNull { search("", it) } ?: return null
        for (hit in kugouPick(candidates, song.title, song.artist, song.durationSec)) {
            fun download(fmt: String) = fetch(url("https://lyrics.kugou.com/download", "fmt" to fmt, "charset" to "utf8", "client" to "pc", "ver" to "1",
                "id" to hit.getString("id"), "accesskey" to hit.getString("accesskey")), end)?.let { JSONObject(it).optString("content") }
            val karaoke = download("krc")?.let(::decodeKrc)
            val text = karaoke ?: download("lrc")?.let { String(java.util.Base64.getMimeDecoder().decode(it), Charsets.UTF_8) } ?: continue
            if (otherEdition(text, song.title)) continue
            val lines = (if (karaoke != null) parseKrc(text) else kugouLines(text)) ?: continue
            return Lyrics.Timed(lines, Lyrics.KUGOU)
        }
        null
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
        return record?.let(::fromEntry) ?: tries { closest(song) } ?: titleOnly(song)
    }

    /**
     * The last try on LRCLIB, for a song the artist's spelling kept hidden (a channel called "Nella Kharisma Music"):
     * a search by title alone, accepted only for the same title, within 3 s of the length, by an artist that shares a word with ours.
     */
    private fun titleOnly(song: Song): Lyrics? = runCatching {
        if (song.durationSec <= 0) return null
        val url = BASE.toHttpUrl().newBuilder().addPathSegments("api/search").addQueryParameter("track_name", song.title).build()
        pickSameTitle(JSONArray(call(url.toString()) ?: return null), song.title, song.artist, song.durationSec)
    }.getOrNull()

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
            // the lyrics may hold bare quote marks ("stay with me"), so the text runs to the tag's end, not the first quote
            val body = Regex("""LyricContent="(.*?)"\s*/>""", RegexOption.DOT_MATCHES_ALL).find(raw)?.groupValues?.get(1)?.let(::unescapeXml) ?: raw
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

        private fun unescapeXml(s: String) = decodeEntities(s)

        /** The timed lines of a Unison entry, or null when it is empty or plain text. */
        fun unisonLines(text: String, format: String, syncType: String): List<LyricLine>? {
            if (text.isBlank() || syncType.equals("plain", true)) return null
            return (if (format.equals("ttml", true)) parseTtml(text) else parseLrc(text)).takeIf { it.isNotEmpty() }
        }

        /** Says who is asking, so a lyrics site's owner can reach us instead of blocking us. */
        private val UA get() = "Spotifry/" + AppVersion.name + " (+https://github.com/Alnyz/spotifry)"

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
                val words = ArrayList<Pair<Long, String>>()
                Regex("""<(\d+):(\d+(?:\.\d+)?)>([^<]*)""").findAll(m.groupValues[3].replace(Regex("""\{[^}]*}"""), "")).forEach { w ->
                    val text = w.groupValues[3]
                    // some entries give a space as its own blank tag between two words: it belongs to the word before it
                    if (text.isBlank()) { words.lastOrNull()?.let { (at, t) -> if (!t.endsWith(" ")) words[words.lastIndex] = at to "$t " } }
                    else words += (w.groupValues[1].toLong() * 60_000 + (w.groupValues[2].toDouble() * 1000).toLong()) to text
                }
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

        private fun unescape(s: String) = decodeEntities(s)

        private fun words(s: String) = s.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 3 }.toSet()

        /** From a title-only search: the same title, a length within 3 s, and an artist sharing a word with [artist]. */
        fun pickSameTitle(results: JSONArray, title: String, artist: String, durationSec: Long): Lyrics? {
            val ours = words(artist)
            return (0 until results.length()).map { results.getJSONObject(it) }
                .filter { it.optString("trackName").trim().equals(title.trim(), true) && abs(it.optDouble("duration", 0.0) - durationSec) <= 3 &&
                    words(it.optString("artistName")).any { w -> w in ours } }
                .sortedByDescending { !it.isNull("syncedLyrics") }
                .firstNotNullOfOrNull { fromEntry(it)?.takeUnless(::isJunk) }
        }

        private val BRACKETS = Regex("""[(（].*?[)）]""")

        private fun same(a: String, b: String) = a.lowercase().filter { it.isLetterOrDigit() } == b.lowercase().filter { it.isLetterOrDigit() }

        /** The file hashes of KuGou song-search hits that are this song (same title, an artist sharing a word, length within 8 s), the nearest first, up to 3. */
        fun kugouHashes(lists: JSONArray, title: String, artist: String, durationSec: Long): List<String> {
            val ours = words(artist)
            fun length(o: JSONObject) = o.optString("Duration").toDoubleOrNull() ?: 0.0
            return (0 until lists.length()).map { lists.getJSONObject(it) }.filter {
                val name = it.optString("OriSongName").ifBlank { it.optString("SongName") }
                same(name.replace(BRACKETS, ""), title.replace(BRACKETS, "")) && words(it.optString("SingerName")).any { w -> w in ours } &&
                    (durationSec <= 0 || abs(length(it) - durationSec) <= 8)
            }.sortedBy { abs(length(it) - durationSec) }.mapNotNull { it.optString("FileHash").takeIf { h -> h.isNotBlank() } }.take(3)
        }

        /** KuGou's lyric-search candidates that are this song (same title, an artist sharing a word, length within 5 s), the nearest in length first, up to 3. */
        fun kugouPick(candidates: JSONArray, title: String, artist: String, durationSec: Long): List<JSONObject> {
            val ours = words(artist)
            return (0 until candidates.length()).map { candidates.getJSONObject(it) }.filter {
                same(it.optString("song").replace(BRACKETS, ""), title.replace(BRACKETS, "")) && words(it.optString("singer")).any { w -> w in ours } &&
                    (durationSec <= 0 || abs(it.optLong("duration") / 1000.0 - durationSec) <= 5)
            }.sortedBy { abs(it.optLong("duration") / 1000.0 - durationSec) }.take(3)
        }

        /** KuGou's karaoke file: "krc1", then XOR with a fixed key, then zlib. */
        fun decodeKrc(base64: String): String? = runCatching {
            val raw = java.util.Base64.getMimeDecoder().decode(base64)
            val key = intArrayOf(0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69)
            val plain = ByteArray(raw.size - 4) { (raw[it + 4].toInt() xor key[it % 16]).toByte() }
            java.util.zip.InflaterInputStream(plain.inputStream()).readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF")
        }.getOrNull()

        /** "[lineStart,lineLength]<offset,length,0>word<…>" lines as timed words; the title card and credit lines it opens with are dropped. */
        fun parseKrc(text: String): List<LyricLine>? {
            val lines = text.lines().mapNotNull { src ->
                val m = Regex("""^\[(\d+),(\d+)](.*)$""").find(src.trim()) ?: return@mapNotNull null
                val words = Regex("""<(\d+),(\d+),\d+>([^<]*)""").findAll(m.groupValues[3]).map { w ->
                    val at = m.groupValues[1].toLong() + w.groupValues[1].toLong()
                    LyricWord(at, at + w.groupValues[2].toLong(), w.groupValues[3])
                }.filter { it.text.isNotEmpty() }.toList()
                val line = words.joinToString("") { it.text }.trim()
                if (line.isEmpty()) null else LyricLine(m.groupValues[1].toLong(), line, words)
            }.sortedBy { it.atMs }
            return lines.dropWhile { " - " in it.text && it.atMs < 3_000 }.filter { "：" !in it.text }.takeIf { it.isNotEmpty() }
        }

        /** Lyrics whose own "[ti:…]" title names another edition ("(Live)", "(Remix)"…) than the song's. */
        fun otherEdition(lrc: String, title: String): Boolean {
            val ti = Regex("""\[ti:([^\]]*)]""").find(lrc)?.groupValues?.get(1) ?: return false
            val edition = Regex("""\b(live|remix|acoustic|instrumental|sped up|slowed|radio edit|single version|karaoke|demo)\b""", RegexOption.IGNORE_CASE)
            return edition.findAll(ti).any { e -> !title.contains(e.value, ignoreCase = true) }
        }

        /**
         * KuGou's file as lyric lines: the "Title - Artist" and "Lyricist: …" lines it opens and closes with are dropped
         * (they carry real times and would be sung), and a file that is only its "pure music" notice is no lyrics.
         */
        fun kugouLines(lrc: String): List<LyricLine>? {
            fun credit(l: LyricLine) = Regex("""^.{1,20}[:：]\s*\S""").containsMatchIn(l.text)
            val lines = parseLrc(lrc).dropWhile { credit(it) || (" - " in it.text && it.atMs < 1_000) }.dropLastWhile { credit(it) }
            if (lines.isEmpty() || lines.all { Regex("纯音乐.*欣赏").containsMatchIn(it.text) }) return null
            return lines
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
private val ENTITY = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|amp|lt|gt|quot|apos|nbsp);")

/** Web codes some lyrics sites send ("&#x27;", "&amp;") turned back into their characters, in one pass; unknown ones stay. */
fun decodeEntities(s: String): String = if ('&' !in s) s else ENTITY.replace(s) { m ->
    when (val e = m.groupValues[1]) {
        "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
        else -> {
            val code = if (e[1] == 'x' || e[1] == 'X') e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull()
            code?.takeIf { Character.isValidCodePoint(it) && it != 0 }?.let { String(Character.toChars(it)) } ?: m.value
        }
    }
}

/** The same lyrics with web codes decoded, whichever site they came from. */
fun Lyrics.cleaned(): Lyrics = when (this) {
    is Lyrics.Plain -> copy(lines = lines.map(::decodeEntities))
    is Lyrics.Timed -> copy(lines = lines.map { l -> l.copy(text = decodeEntities(l.text), words = l.words.map { it.copy(text = decodeEntities(it.text)) }) })
}

fun List<LyricLine>.indexAt(positionMs: Long): Int = indexOfLast { it.atMs <= positionMs }
