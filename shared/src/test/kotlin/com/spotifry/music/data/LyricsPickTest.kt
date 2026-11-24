package com.spotifry.music.data

import com.spotifry.music.data.source.Song
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsPickTest {
    private val song = Song("id", "Konco Mesra", "Nella Kharisma Official", 301, null)
    private val entry = JSONObject().put("duration", 301.0).put("syncedLyrics", "[00:01.00] first line").put("plainLyrics", JSONObject.NULL)

    /** Only LRCLIB has the song; every other site says no. Every request is counted. */
    private fun source(asked: MutableList<String> = mutableListOf(), off: Set<String> = emptySet()) = LyricsSource(OkHttpClient.Builder().addInterceptor { c ->
        asked += c.request().url.host
        val ok = c.request().url.host == "lrclib.net" && c.request().url.encodedPath.endsWith("/get")
        Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(if (ok) 200 else 404).message("x")
            .body((if (ok) entry.toString() else "").toResponseBody()).build()
    }.build(), off = { off })

    @Test fun eachSourceAnswersOnItsOwn() = runBlocking {
        val s = source()
        assertEquals("LRCLIB", (s.from(song, Lyrics.LRCLIB) as Lyrics.Timed).from)
        assertNull(s.from(song, Lyrics.DEEZER))
    }

    @Test fun aSourceIsAskedOnceAndRemembered() = runBlocking {
        val asked = mutableListOf<String>()
        val s = source(asked)
        s.from(song, Lyrics.LRCLIB); s.from(song, Lyrics.LRCLIB)
        assertEquals(1, asked.count { it == "lrclib.net" })
    }

    @Test fun aPickWithNothingFallsBackToTheBestMatch() = runBlocking {
        val s = source()
        assertEquals("LRCLIB", (s.get(song, pick = Lyrics.DEEZER) as Lyrics.Timed).from)
    }

    @Test fun aPickOfASourceTurnedOffIsIgnored() = runBlocking {
        val asked = mutableListOf<String>()
        val s = source(asked, off = setOf(Lyrics.LRCLIB))
        assertNull(s.get(song, pick = Lyrics.LRCLIB))
        assertEquals(0, asked.count { it == "lrclib.net" })
    }

    @Test fun onlyTheSourcesLeftOnAreListed() {
        assertEquals(Lyrics.SOURCES - setOf(Lyrics.NETEASE, Lyrics.UNISON), source(off = setOf(Lyrics.NETEASE, Lyrics.UNISON)).enabledSources())
    }

    @Test fun lyricsAreGroupedByHowTheyAreTimed() {
        val word = Lyrics.Timed(listOf(LyricLine(0, "a", listOf(LyricWord(0, 5, "a")))))
        assertEquals(LyricsKind.WORD, word.kind())
        assertEquals(LyricsKind.LINE, Lyrics.Timed(listOf(LyricLine(0, "a"))).kind())
        assertEquals(LyricsKind.PLAIN, Lyrics.Plain(listOf("a")).kind())
        assertNotNull(word)
    }
}
