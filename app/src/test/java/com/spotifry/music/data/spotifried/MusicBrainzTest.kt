package com.spotifry.music.data.spotifried

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicBrainzTest {
    private val server = MockWebServer()
    private fun mb() = MusicBrainz(OkHttpClient(), server.url("/").toString().trimEnd('/'), "Spotifry/1 (test)")

    @After fun stop() = runCatching { server.shutdown() }.let { }

    private fun rec(title: String, band: String, date: String, score: Int = 100) =
        """{"title":"$title","score":$score,"first-release-date":"$date","artist-credit":[{"name":"$band","artist":{"name":"$band"}}],"releases":[]}"""

    private fun body(vararg r: String) = """{"recordings":[${r.joinToString(",")}]}"""

    @Test fun takesTheEarliestYearNotTheFirstResult() {
        val j = body(rec("Fly", "Band", "1992-03"), rec("Fly", "Band", "1975-09-12"), rec("Fly", "Band", "1980"))
        assertEquals(1975, earliestYear(j, "Fly", "Band"))
    }

    @Test fun ignoresLowScore() = assertNull(earliestYear(body(rec("Fly", "Band", "1975", score = 80)), "Fly", "Band"))

    @Test fun ignoresADifferentTitle() = assertNull(earliestYear(body(rec("Other", "Band", "1975")), "Fly", "Band"))

    @Test fun needsOneArtistInCommon() = assertNull(earliestYear(body(rec("Fly", "Other Band", "1975")), "Fly", "Band"))

    @Test fun cleansExtrasFromTheSongTitle() =
        assertEquals(2022, earliestYear(body(rec("Kesariya", "Arijit Singh", "2022-07-17")), "Kesariya (From Brahmastra)", "Arijit Singh"))

    @Test fun sendsTheUserAgent() = runBlocking {
        server.enqueue(MockResponse().setBody(body()))
        mb().yearOf("Fly", "Band")
        assertEquals("Spotifry/1 (test)", server.takeRequest().getHeader("User-Agent"))
    }

    @Test fun findsTheYearOverHttp() = runBlocking {
        server.enqueue(MockResponse().setBody(body(rec("Fly", "Band", "1975"))))
        assertEquals(YearResult.Found(1975), mb().yearOf("Fly", "Band"))
    }

    @Test fun status503IsTryLater() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(YearResult.TryLater, mb().yearOf("Fly", "Band"))
    }

    @Test fun status429IsTryLater() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(YearResult.TryLater, mb().yearOf("Fly", "Band"))
    }

    @Test fun emptyResultIsNotFound() = runBlocking {
        server.enqueue(MockResponse().setBody(body()))
        assertEquals(YearResult.NotFound, mb().yearOf("Fly", "Band"))
    }

    @Test fun garbageBodyIsTryLater() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>nope"))
        assertEquals(YearResult.TryLater, mb().yearOf("Fly", "Band"))
    }

    @Test fun nothingElseIsSent() = runBlocking {
        server.enqueue(MockResponse().setBody(body()))
        mb().yearOf("Say \"hi\"", "Band")
        val url = server.takeRequest().requestUrl!!
        assertEquals(setOf("query", "fmt", "limit"), url.queryParameterNames)
        assertEquals("recording:\"Say \\\"hi\\\"\" AND artist:\"Band\"", url.queryParameter("query"))
        assertEquals("100", url.queryParameter("limit"))
        assertTrue(url.encodedPath == "/ws/2/recording")
    }
}
