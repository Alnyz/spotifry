package com.spotifry.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatesTest {
    private fun release(tag: String, vararg assets: String, draft: Boolean = false) =
        """{"tag_name":"$tag","html_url":"https://github.com/x/releases/tag/$tag","draft":$draft,"prerelease":false,"assets":[${assets.joinToString(",") { "{\"name\":\"$it\"}" }}]}"""

    @Test fun phoneReleasesAreSkipped() {
        val json = "[" + listOf(release("v0.14.0", "spotifry-0.14.0.apk"), release("desktop-v0.3.0", "Spotifry-0.3.0-x86_64.AppImage"), release("desktop-v0.2.0", "Spotifry-0.2.0-x86_64.AppImage")).joinToString(",") + "]"
        assertEquals(DesktopRelease("0.3.0", "https://github.com/x/releases/tag/desktop-v0.3.0"), newestDesktopRelease(json, windows = false))
    }

    @Test fun draftsDontCountAndNoAppImageIsNothing() {
        assertNull(newestDesktopRelease("[" + release("desktop-v0.4.0", "Spotifry-0.4.0-x86_64.AppImage", draft = true) + "," + release("v0.14.0", "a.apk") + "]", windows = false))
    }

    @Test fun eachSystemLooksForItsOwnFile() {
        val json = "[" + release("desktop-v0.7.0", "Spotifry-0.7.0-windows-x64.msi") + "," + release("desktop-v0.6.0", "Spotifry-0.6.0-x86_64.AppImage") + "]"
        assertEquals("0.7.0", newestDesktopRelease(json, windows = true)!!.version)
        assertEquals("0.6.0", newestDesktopRelease(json, windows = false)!!.version)
    }

    @Test fun theNewestWinsWhateverTheOrder() {
        val json = "[" + release("d2", "Spotifry-0.2.0-x86_64.AppImage") + "," + release("d10", "Spotifry-0.10.0-x86_64.AppImage") + "]"
        assertEquals("0.10.0", newestDesktopRelease(json, windows = false)!!.version)
    }

    @Test fun theReleaseKeepsItsDownloadAddressSizeAndChecksum() {
        val sum = "a".repeat(64)
        val json = """[{"tag_name":"d","html_url":"page","draft":false,"prerelease":false,"assets":[{"name":"Spotifry-0.8.0-x86_64.AppImage","browser_download_url":"https://github.com/x/f","size":42,"digest":"sha256:$sum"}]}]"""
        assertEquals(DesktopRelease("0.8.0", "page", "https://github.com/x/f", 42, sum), newestDesktopRelease(json, windows = false))
    }

    @Test fun swappingReplacesTheFileAndMakesItRunnable() {
        val dir = createTempDir().also { it.deleteOnExit() }
        val old = java.io.File(dir, "Spotifry.AppImage").apply { writeText("old") }
        val new = java.io.File(dir, "Spotifry.AppImage.new").apply { writeText("new") }
        swapIn(new, old)
        assertEquals("new", old.readText()); assertEquals(false, new.exists()); assertEquals(true, old.canExecute())
    }

    @Test fun installDownloadsChecksAndReplacesTheRunningFile() {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val sum = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f") { ex -> ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) } }
        server.start()
        try {
            val dir = createTempDir().also { it.deleteOnExit() }
            val running = java.io.File(dir, "Spotifry.AppImage").apply { writeText("old") }
            val base = "http://127.0.0.1:${server.address.port}/"
            val updates = Updates(okhttp3.OkHttpClient(), com.spotifry.music.data.MemoryPrefs(), files = base)
            updates.install(DesktopRelease("9.9.9", "page", base + "f", bytes.size.toLong(), sum), running.path)
            val end = System.currentTimeMillis() + 15_000
            while (updates.progress.value?.step == UpdateStep.Working && System.currentTimeMillis() < end) Thread.sleep(50)
            assertEquals(UpdateStep.Done, updates.progress.value?.step ?: error("nothing happened"))
            assertEquals(bytes.size.toLong(), running.length()); assertEquals(true, running.canExecute())
            assertEquals(listOf("Spotifry.AppImage"), dir.list()!!.toList())
        } finally { server.stop(0) }
    }
}
