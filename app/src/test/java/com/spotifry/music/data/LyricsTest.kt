package com.spotifry.music.data

import com.spotifry.music.data.source.Song
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LyricsTest {
    @Test fun readsTimedLinesAndDropsEmptyOnes() {
        val lines = LyricsSource.parseLrc("[00:04.44]Easy come\n[00:08.30] Easy go\n[00:10.00]\n[01:02.5]Last")
        assertEquals(listOf(4_440L, 8_300L, 62_500L), lines.map { it.atMs })
        assertEquals("Easy go", lines[1].text)
        assertEquals(-1, lines.indexAt(1_000))
        assertEquals(1, lines.indexAt(9_000))
    }

    @Test fun prefersTimedWithinFiveSecondsOfTheSong() {
        val results = JSONArray()
            .put(JSONObject().put("duration", 262.0).put("syncedLyrics", "[00:01.00]music video cut").put("plainLyrics", "x"))
            .put(JSONObject().put("duration", 248.0).put("syncedLyrics", JSONObject.NULL).put("plainLyrics", "plain one"))
            .put(JSONObject().put("duration", 246.0).put("syncedLyrics", "[00:02.00]album cut").put("plainLyrics", "y"))
        val l = LyricsSource.pickClosest(results, 248) as Lyrics.Timed
        assertEquals("album cut", l.lines.single().text)
    }

    @Test fun instrumentalOrEmptyHasNoLyrics() {
        assertNull(LyricsSource.fromEntry(JSONObject().put("instrumental", true).put("plainLyrics", "la")))
        assertNull(LyricsSource.fromEntry(JSONObject().put("syncedLyrics", JSONObject.NULL).put("plainLyrics", JSONObject.NULL)))
        assertTrue(LyricsSource.fromEntry(JSONObject().put("syncedLyrics", JSONObject.NULL).put("plainLyrics", "a\n\nb")) is Lyrics.Plain)
    }

    private val song = Song("id", "GLORY", "Ogryzek", 149, null)
    private fun plain(vararg lines: String) = JSONObject().put("duration", 149.0).put("syncedLyrics", JSONObject.NULL).put("plainLyrics", lines.joinToString("\n"))

    /** LRCLIB stand-in: [get] answers /api/get (null = 404), [search] answers /api/search; every request path is logged. */
    private fun lrclib(get: JSONObject?, search: JSONArray, asked: MutableList<String>) = LyricsSource(OkHttpClient.Builder().addInterceptor { c ->
        val path = c.request().url.encodedPath; asked += path
        val body = if (path.endsWith("/get")) get?.toString() else search.toString()
        Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(if (body == null) 404 else 200).message("x")
            .body((body ?: "").toResponseBody()).build()
    }.build(), off = { setOf(Lyrics.BETTER_LYRICS, Lyrics.LRC_RED, Lyrics.PORTATO, Lyrics.SIMPMUSIC, Lyrics.NETEASE, Lyrics.DEEZER, Lyrics.UNISON) })

    @Test fun instrumentalExactMatchIsFinalAndSkipsTheSearch() = runBlocking {
        val asked = mutableListOf<String>()
        val get = JSONObject().put("instrumental", true).put("plainLyrics", JSONObject.NULL).put("syncedLyrics", JSONObject.NULL)
        assertNull(lrclib(get, JSONArray().put(plain("a", "b")), asked).get(song))
        assertEquals(listOf("/api/get"), asked)
    }

    @Test fun missingExactRecordStillTriesTheSearch() = runBlocking {
        val asked = mutableListOf<String>()
        assertTrue(lrclib(null, JSONArray().put(plain("a", "b")), asked).get(song) is Lyrics.Plain)
        assertEquals(listOf("/api/get", "/api/search"), asked)
    }

    private val junk = Array(16) { "1nst pinar.tncc" }

    @Test fun searchSkipsRepeatedLineJunkAndTakesTheNextOne() {
        val l = LyricsSource.pickClosest(JSONArray().put(plain(*junk)).put(plain("real", "words")), 149) as Lyrics.Plain
        assertEquals(listOf("real", "words"), l.lines)
        assertNull(LyricsSource.pickClosest(JSONArray().put(plain(*junk)), 149))
    }

    @Test fun searchKeepsSyncedEvenIfRepeatedAndShortRepeatsPlain() {
        val synced = JSONObject().put("duration", 149.0).put("plainLyrics", JSONObject.NULL).put("syncedLyrics", (1..20).joinToString("\n") { "[00:%02d.00]la".format(it) })
        assertTrue(LyricsSource.pickClosest(JSONArray().put(synced), 149) is Lyrics.Timed)
        assertTrue(LyricsSource.pickClosest(JSONArray().put(plain("la", "la", "la", "la", "la")), 149) is Lyrics.Plain)
    }

    @Test fun exactMatchWithRepeatedLinesIsAccepted() = runBlocking {
        val l = lrclib(plain(*Array(20) { "na" }), JSONArray(), mutableListOf()).get(song)
        assertEquals(20, (l as Lyrics.Plain).lines.size)
    }

    // trimmed from a real BetterLyrics answer: syllables of one word, a background voice, a line with no word timing
    private val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" itunes:timing="Word"><head></head><body dur="4:11.668">""" +
        """<div begin="9.147" end="20.0"><p begin="9.147" end="15.034" ttm:agent="v2"><span begin="9.147" end="10.363">I,</span> """ +
        """<span begin="11.520" end="11.899">I</span> <span begin="17.603" end="18.155">say</span> <span begin="18.155" end="18.393">good</span>""" +
        """<span begin="18.393" end="19.844">bye</span><span ttm:role="x-bg"><span begin="19.0" end="19.5">(ooh)</span></span></p>""" +
        """<p begin="1:02.5" end="1:05.0">Rock &amp; roll</p></div></body></tt>"""

    @Test fun readsWordTimesFromAppleLyrics() {
        val lines = LyricsSource.parseTtml(ttml)
        assertEquals(2, lines.size)
        assertEquals(9_147L, lines[0].atMs)
        assertEquals("I, I say goodbye", lines[0].text)
        assertEquals(listOf("I, ", "I ", "say ", "good", "bye"), lines[0].words.map { it.text })
        assertEquals(18_393L, lines[0].words.last().startMs)
        assertEquals(62_500L, lines[1].atMs)
        assertEquals("Rock & roll", lines[1].text)
        assertTrue(lines[1].words.isEmpty())
    }

    @Test fun clockReadsEveryForm() {
        assertEquals(62_345L, LyricsSource.clock("1:02.345"))
        assertEquals(62_345L, LyricsSource.clock("62.345"))
        assertEquals(3_662_300L, LyricsSource.clock("1:01:02.3"))
    }

    @Test fun lrcRedHitMustBeTheSameRecording() {
        val hits = JSONArray("""[
            {"isrc":"GBUM71029604","title":"Bohemian Rhapsody (Live)","artist":"Queen","duration":352.0},
            {"isrc":"GBUM71029605","title":"Bohemian Rhapsody - 2011 Remaster","artist":"Queen","duration":356.0},
            {"isrc":"GBUM71029606","title":"Bohemian Rhapsody","artist":"Someone Else","duration":355.0}]""")
        assertEquals("GBUM71029605", LyricsSource.bestHit(hits, "Bohemian Rhapsody", "Queen", 355))
        assertNull(LyricsSource.bestHit(hits, "Bohemian Rhapsody", "Queen", 300))
        assertEquals("GBUM71029604", LyricsSource.bestHit(hits, "Bohemian Rhapsody (Live)", "Queen", 352))
    }

    @Test fun unisonEntryShapes() {
        assertNull(LyricsSource.unisonLines("la la", "lrc", "plain"))
        assertNull(LyricsSource.unisonLines("", "lrc", "linesync"))
        assertEquals(2, LyricsSource.unisonLines("[00:01.00]a\n[00:02.00]b", "lrc", "linesync")!!.size)
    }

    @Test fun qqKaraokeLinesKeepWordsAndDropCredits() {
        val raw = "<Lyric_1 LyricType=\"1\" LyricContent=\"[ti:Here]\n[0,1660]Here(0,75) (75,75)-(525,75) (600,75)Alessia(675,75)\n" +
            "[1660,1660]Lyrics(1660,47) (1707,47)by(1754,47)：(1801,47)Someone(1848,47)\n" +
            "[4984,1956]I (4984,178)guess (5162,167)right (5329,167)\n\"/>"
        val lines = LyricsSource.parseQrc(raw)
        assertEquals(1, lines.size)
        assertEquals("I guess right", lines[0].text)
        assertEquals(listOf("I ", "guess ", "right "), lines[0].words.map { it.text })
        assertEquals(5162L, lines[0].words[1].startMs)
    }

    @Test fun readsSimpMusicWordsAndDropsTags() {
        val raw = "[00:27.39] <00:27.39>I <00:27.54>been <00:27.74>tryna <00:28.07>call\n[00:30.18] {agent:v2} <00:30.18>I've <00:30.39>been {bg}<00:30.64>on\n[00:31.00]\nnot a line"
        val lines = LyricsSource.parseRichSync(raw)
        assertEquals(listOf(27_390L, 30_180L), lines.map { it.atMs })
        assertEquals("I been tryna call", lines[0].text)
        assertEquals(listOf(27_390L, 27_540L, 27_740L, 28_070L), lines[0].words.map { it.startMs })
        assertEquals(27_540L, lines[0].words[0].endMs)
        assertEquals("I've been on", lines[1].text)
        assertEquals(30_180L, lines[1].words.first().startMs)
    }

    @Test fun simpMusicPicksTheEntryNearestInLengthAndNeverBeyondThreeSeconds() {
        val a = JSONObject().put("durationSeconds", 240).put("richSyncLyrics", "[00:01.00] <00:01.00>wrong <00:01.50>cut")
        val b = JSONObject().put("durationSeconds", 201).put("richSyncLyrics", "[00:02.00] <00:02.00>right <00:02.50>cut")
        assertEquals("right cut", LyricsSource.simpMusicLines(JSONArray().put(a).put(b), 200)!!.single().text)
        assertNull(LyricsSource.simpMusicLines(JSONArray().put(a), 200))
        assertNull(LyricsSource.simpMusicLines(JSONArray().put(JSONObject().put("durationSeconds", 200).put("syncedLyrics", "[00:01.00]line only")), 200))
    }

    @Test fun readsNetEaseWordsAndDropsCreditLines() {
        val raw = "{\"t\":0,\"c\":[{\"tx\":\"meta\"}]}\n[0,1000](0,1000,0)作词 : Someone\n[1000,1000](1000,1000,0)Lyrics by: Other\n" +
            "[22720,4620](22720,270,0)what (22990,180,0)you (23170,600,0)want (23770,210,0)me\n[30340,3300](30340,510,0)Don't (30850,240,0)know"
        val lines = LyricsSource.parseYrc(raw)
        assertEquals(listOf("what you want me", "Don't know"), lines.map { it.text })
        assertEquals(listOf(22_720L, 22_990L, 23_170L, 23_770L), lines[0].words.map { it.startMs })
        assertEquals(22_990L, lines[0].words[0].endMs)
        assertEquals("what ", lines[0].words[0].text)
    }

    @Test fun deezerWordsGetTheirSpacesBack() {
        fun line(vararg w: Pair<Long, String>) = JSONObject().put("words", JSONArray().also { a -> w.forEach { (t, x) -> a.put(JSONObject().put("start", t).put("end", t + 100).put("word", x)) } })
        val lines = JSONArray().put(line(1000L to "Hey", 1200L to ",", 1400L to "you")).put(line(5000L to "夜", 5100L to "曲", 5200L to "(oh)"))
        val out = LyricsSource.deezerLines(lines, 200)
        assertEquals("Hey, you", out[0].text)
        assertEquals("夜曲 (oh)", out[1].text)
        assertEquals(listOf("Hey", ",", "you"), out[0].words.map { it.text.trim() })
        assertTrue(LyricsSource.spaceBetween("don't", "know"))
        assertTrue(!LyricsSource.spaceBetween("know", "!"))
        assertTrue(!LyricsSource.spaceBetween("(", "oh"))
    }

    @Test fun deezerLyricsThatRunFarPastTheSongAreAnotherVersion() {
        val w = JSONArray().put(JSONObject().put("words", JSONArray().put(JSONObject().put("start", 460_000).put("end", 466_000).put("word", "gone"))))
        assertTrue(LyricsSource.deezerLines(w, 393).isEmpty())
        assertEquals(1, LyricsSource.deezerLines(w, 466).size)
    }

    @Test fun neteaseAndDeezerHitsMustBeTheSameRecording() {
        val ne = JSONArray()
            .put(JSONObject().put("id", 1).put("name", "Numb (Live)").put("duration", 187_000).put("artists", JSONArray().put(JSONObject().put("name", "Linkin Park"))))
            .put(JSONObject().put("id", 2).put("name", "Numb").put("duration", 195_000).put("artists", JSONArray().put(JSONObject().put("name", "Linkin Park"))))
            .put(JSONObject().put("id", 3).put("name", "Numb").put("duration", 187_500).put("artists", JSONArray().put(JSONObject().put("name", "Cover Band"))))
            .put(JSONObject().put("id", 4).put("name", "Numb").put("duration", 188_000).put("artists", JSONArray().put(JSONObject().put("name", "Linkin Park"))))
        assertEquals(4L, LyricsSource.neteaseId(ne, "Numb", "Linkin Park", 187))
        assertNull(LyricsSource.neteaseId(ne, "Numb", "Linkin Park", 170))
        val dz = JSONArray()
            .put(JSONObject().put("id", 9).put("title", "Numb - Remastered").put("title_short", "Numb").put("duration", 187).put("artist", JSONObject().put("name", "Linkin Park")))
        assertEquals(9L, LyricsSource.deezerId(dz, "Numb", "Linkin Park", 188))
        assertNull(LyricsSource.deezerId(dz, "Numb", "Someone Else", 188))
    }
}
