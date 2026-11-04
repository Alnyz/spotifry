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
}
