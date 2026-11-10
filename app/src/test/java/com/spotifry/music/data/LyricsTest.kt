package com.spotifry.music.data

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
}
