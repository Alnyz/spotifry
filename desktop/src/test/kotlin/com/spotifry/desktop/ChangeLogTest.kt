package com.spotifry.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChangeLogTest {
    private fun release(tag: String, body: String, date: String = "2026-11-14T09:00:00Z", extra: String = "") =
        """{"tag_name":"$tag","body":${org.json.JSONObject.quote(body)},"published_at":"$date"$extra}"""

    @Test fun onlyDesktopReleasesAreListedInOrder() {
        val json = "[" + listOf(
            release("desktop-v0.9.0", "New settings"), release("v0.16.1", "phone"), release("desktop-v0.8.0", "Update inside the app", "2026-11-10T00:00:00Z"),
            release("desktop-v0.7.0", "draft", extra = ""","draft":true"""), release("desktop-v0.6.0", "beta", extra = ""","prerelease":true"""),
        ).joinToString(",") + "]"
        val log = parseDesktopLog(json)
        assertEquals(listOf("0.9.0", "0.8.0"), log.map { it.version })
        assertEquals("2026-11-10T00:00:00Z", log[1].date)
    }

    @Test fun theSharedReleaseCountsAndPhoneOnlyOnesDont() {
        val json = "[" + listOf(
            release("android-v1.0.1", "phone fixes", extra = ""","assets":[{"name":"spotifry-1.0.1.apk"}]"""),
            release("v1.0.0", "both", extra = ""","assets":[{"name":"spotifry-1.0.0.apk"},{"name":"Spotifry-1.0.0-x86_64.AppImage"}]"""),
        ).joinToString(",") + "]"
        assertEquals(listOf("1.0.0"), parseDesktopLog(json).map { it.version })
    }

    @Test fun notesLoseTheirMarkdown() {
        val md = "## What's new\n\n- **Settings** page\n* Fixed `Opus` export\n\n\n\nSee [the site](https://x.y/z)."
        assertEquals("What's new\n\n• Settings page\n• Fixed Opus export\n\nSee the site.", plainNotes(md))
        assertEquals("", plainNotes("  \n "))
    }

    @Test fun datesReadLikeTheMockup() {
        assertEquals("14 Nov 2026", logDate("2026-11-14T09:12:00Z"))
        assertNull(logDate(""))
    }

    @Test fun theLastAnswerIsKeptOnDisk() {
        val file = java.io.File.createTempFile("change_log", ".json")
        file.writeText("""[{"v":"0.8.0","d":"2026-11-10T00:00:00Z","n":"Update inside the app"}]""")
        val log = ChangeLog(okhttp3.OkHttpClient(), file)
        assertEquals(listOf(DesktopLogEntry("0.8.0", "2026-11-10T00:00:00Z", "Update inside the app")), log.entries.value)
        file.delete()
        assertEquals(emptyList<DesktopLogEntry>(), ChangeLog(okhttp3.OkHttpClient(), file).entries.value)
    }
}
