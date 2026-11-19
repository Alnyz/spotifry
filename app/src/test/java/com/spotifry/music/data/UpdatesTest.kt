package com.spotifry.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdatesTest {
    @Test fun comparesVersionsByNumber() {
        assertTrue(isNewer("0.2.0", "0.1.0"))
        assertTrue(isNewer("v0.10.0", "0.9.2"))
        assertTrue(isNewer("1.0", "0.9.9"))
        assertFalse(isNewer("0.1.0", "0.1.0"))
        assertFalse(isNewer("v0.1.0", "0.2.0"))
    }

    @Test fun readsGithubsLatestRelease() {
        val json = """
            {"tag_name":"v0.2.0","body":"Search and order inside playlists.\n\n- More\n",
             "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":3},
                       {"name":"spotifry-0.2.0.apk","browser_download_url":"https://x/spotifry-0.2.0.apk","size":7100000}]}
        """.trimIndent()
        val r = parseRelease(json)!!
        assertEquals("0.2.0", r.version)
        assertEquals("https://x/spotifry-0.2.0.apk", r.apkUrl)
        assertEquals(7_100_000L, r.sizeBytes)
        assertEquals("Search and order inside playlists.", r.headline)
    }

    @Test fun releaseWithoutApkIsIgnored() = assertNull(parseRelease("""{"tag_name":"v9.0.0","body":"","assets":[]}"""))

    @Test fun headlineSkipsMarkdownMarks() = assertEquals("New things", Release("1", "\n## New things\nmore", "", 0).headline)

    @Test fun skipsDesktopReleasesWhenLookingForThePhones() {
        val json = """
            [{"tag_name":"desktop-v1.1.0","body":"New Home","assets":[{"name":"Spotifry-1.1.0-x86_64.AppImage","browser_download_url":"https://x/a","size":1}]},
             {"tag_name":"android-v1.0.2","body":"Next","prerelease":true,"assets":[{"name":"spotifry-1.0.2.apk","browser_download_url":"https://x/p","size":1}]},
             {"tag_name":"android-v1.0.1","body":"Fixes","assets":[{"name":"spotifry-1.0.1.apk","browser_download_url":"https://x/b","size":2}]},
             {"tag_name":"v1.0.0","body":"First","assets":[{"name":"spotifry-1.0.0.apk","browser_download_url":"https://x/c","size":3}]}]
        """.trimIndent()
        val r = newestPhoneRelease(json)!!
        assertEquals("1.0.1", r.version)
        assertEquals("https://x/b", r.apkUrl)
        assertEquals(listOf("1.0.2", "1.0.1", "1.0.0"), parseReleases(json).map { it.version })
    }
}
