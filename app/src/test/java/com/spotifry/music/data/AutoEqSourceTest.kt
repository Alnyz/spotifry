package com.spotifry.music.data

import com.spotifry.music.player.AutoEqBadFile
import com.spotifry.music.player.AutoEqEntry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class AutoEqSourceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val line2 = "- [Sennheiser HD 600](./oratory1990/over-ear/Sennheiser%20HD%20600) by oratory1990"
    private val line = "- [Sony WH-1000XM5](./Rtings/Bruel%20%26%20Kjaer%205128%20over-ear/Sony%20WH-1000XM5) by Rtings on Bruel & Kjaer 5128"
    private fun source(min: Int = 1) = AutoEqSource(OkHttpClient(), tmp.root, server.url("/results").toString().trimEnd('/'), min)

    @After fun stop() = runCatching { server.shutdown() }.let { }

    @Test fun listIsKeptOnDiskAndUsedWhenOffline() = runBlocking {
        server.enqueue(MockResponse().setBody(line))
        assertEquals("Sony WH-1000XM5", source().index().single().name)
        // a week later the fetch fails, and the saved list still works
        java.io.File(tmp.root, "INDEX.md").setLastModified(0)
        server.shutdown()
        assertEquals("Sony WH-1000XM5", source().index().single().name)
    }

    @Test fun noConnectionAndNoSavedListIsAnIOException() = runBlocking {
        server.shutdown()
        assertTrue(runCatching { source().index() }.exceptionOrNull() is IOException)
    }

    @Test fun serverErrorIsAnIOException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(runCatching { source().index() }.exceptionOrNull() is IOException)
    }

    @Test fun missingMeasurementIsItsOwnError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(runCatching { source().curve(AutoEqEntry("x", "y", null, "y/x")) }.exceptionOrNull() is AutoEqBadFile)
    }

    @Test fun damagedSavedListIsReplacedNotTrusted() = runBlocking {
        // a cut-off list from an earlier run: fresh by date, but far too short
        java.io.File(tmp.root, "INDEX.md").writeText("$line\n- [Bro")
        server.enqueue(MockResponse().setBody("$line\n$line2"))
        assertEquals(2, source(min = 2).index().size)
        assertEquals("$line\n$line2", java.io.File(tmp.root, "INDEX.md").readText())
        assertTrue(!java.io.File(tmp.root, "INDEX.md.part").exists())
    }

    @Test fun damagedDownloadNeverReplacesAGoodSavedList() = runBlocking {
        java.io.File(tmp.root, "INDEX.md").apply { writeText("$line\n$line2"); setLastModified(0) }
        server.enqueue(MockResponse().setBody(line))
        assertEquals(2, source(min = 2).index().size)
        assertEquals("$line\n$line2", java.io.File(tmp.root, "INDEX.md").readText())
    }

    @Test fun anAnswerOverTheCapIsABadFile() = runBlocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(4_000_001))))
        assertTrue(runCatching { source().curve(AutoEqEntry("x", "y", null, "y/x")) }.exceptionOrNull() is AutoEqBadFile)
    }

    @Test fun measurementIsAskedForByItsEncodedPath() = runBlocking {
        server.enqueue(MockResponse().setBody("frequency,raw,target\n" + (20..40).joinToString("\n") { "$it,1,0" }))
        val c = source().curve(AutoEqEntry("Sony WH-1000XM5", "Rtings", "Bruel & Kjaer 5128", "Rtings/Bruel & Kjaer 5128 over-ear/Sony WH-1000XM5"))
        assertEquals(21, c.hz.size)
        assertEquals("/results/Rtings/Bruel%20&%20Kjaer%205128%20over-ear/Sony%20WH-1000XM5/Sony%20WH-1000XM5.csv", server.takeRequest().path)
    }

    @Test fun brokenMeasurementIsAnIOException() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>nope</html>"))
        assertTrue(runCatching { source().curve(AutoEqEntry("x", "y", null, "y/x")) }.exceptionOrNull() is AutoEqBadFile)
    }
}
