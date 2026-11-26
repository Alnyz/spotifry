package com.spotifry.music.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsClosestTest {
    private fun hit(title: String, duration: Double, line: String) =
        JSONObject().put("trackName", title).put("duration", duration).put("syncedLyrics", "[00:01.00] $line").put("plainLyrics", JSONObject.NULL)

    // the music video upload is 332 s; the song itself is 311 s and "Satru 2" happens to be 336 s
    @Test fun aSequelAsLongAsTheVideoIsNotTaken() {
        val results = JSONArray().put(hit("Satru", 311.0, "unine batin")).put(hit("Satru 2", 336.0, "wis tak pupuk"))
        assertNull(LyricsSource.pickClosest(results, 332, "SATRU"))
    }

    @Test fun theSameSongWithCreditsIsTaken() {
        val results = JSONArray().put(hit("Satru 2", 309.0, "wis tak pupuk")).put(hit("Satru (feat. Happy Asmara)", 311.0, "unine batin"))
        assertEquals("unine batin", (LyricsSource.pickClosest(results, 310, "SATRU") as Lyrics.Timed).lines.first().text)
    }

    @Test fun smallSpellingDifferencesStillMatch() {
        assertTrue(LyricsSource.nearlySameTitle("Kartonyono Medot Janji", "Kartonyono Medhot Janji"))
        assertFalse(LyricsSource.nearlySameTitle("Satru 2", "Satru"))
        assertFalse(LyricsSource.nearlySameTitle("Sigar", "Satru"))
    }
}
