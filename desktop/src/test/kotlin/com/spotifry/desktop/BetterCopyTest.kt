package com.spotifry.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BetterCopyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun youtubeTakesItsRangeInTheLinkAndOthersInAHeader() {
        val yt = ranged("https://rr1---sn-x.googlevideo.com/videoplayback?id=1", 0, null)
        assertEquals("0-", yt.url.queryParameter("range")); assertNull(yt.header("Range"))
        assertEquals("100-199", ranged("https://rr1---sn-x.googlevideo.com/videoplayback?id=1&range=5-", 100, 199).url.queryParameter("range"))
        val ll = ranged("https://lossless.example/a.flac", 100, null)
        assertEquals("bytes=100-", ll.header("Range")); assertNull(ll.url.queryParameter("range"))
    }

    @Test fun theBestKeptCopyPlaysAndABetterOneReplacesThePoorer() {
        val dir = tmp.newFolder("replay")
        val store = ReplayStore(OkHttpClient(), CoroutineScope(SupervisorJob() + Dispatchers.IO), dir) { 0 }
        try {
            assertNull(store.best("yt:1"))
            File(dir, "yt_1.youtube").writeText("opus")
            File(dir, "yt_1.saavn.part").writeText("half")
            assertEquals(File(dir, "yt_1.youtube").path to "youtube", store.best("yt:1"))
            val incoming = store.incoming("yt:1", "monochrome").apply { writeText("flac") }
            assertTrue(store.install("yt:1", "monochrome", incoming))
            assertEquals(File(dir, "yt_1.monochrome").path to "monochrome", store.best("yt:1"))
            // the poorer copies, halves included, are gone; the better one is whole
            assertFalse(File(dir, "yt_1.youtube").exists()); assertFalse(File(dir, "yt_1.saavn.part").exists()); assertFalse(incoming.exists())
            assertEquals("flac", File(dir, "yt_1.monochrome").readText())
        } finally { store.close() }
    }
}
