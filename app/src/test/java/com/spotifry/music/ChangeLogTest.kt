package com.spotifry.music

import com.spotifry.music.data.parseReleases
import com.spotifry.music.data.plainNotes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChangeLogTest {
    @Test fun releasesKeepOrderAndSkipDrafts() {
        val json = """[
            {"tag_name":"v0.16.0","body":"New page","published_at":"2026-11-14T09:00:00Z","draft":false},
            {"tag_name":"v0.15.9","body":"x","draft":true},
            {"tag_name":"0.15.2","body":"Fix","published_at":"2026-11-13T09:00:00Z"}]"""
        val list = parseReleases(json)
        assertEquals(listOf("0.16.0", "0.15.2"), list.map { it.version })
        assertEquals("New page", list[0].notes)
        assertEquals("2026-11-14T09:00:00Z", list[0].date)
    }

    @Test fun plainNotesDropMarkdownSymbols() {
        val md = "## What's new\n\n- **Storage** page\n* Fixed `Opus` export\n\n\n\nSee [the site](https://x.y/z)."
        assertEquals("What's new\n\n• Storage page\n• Fixed Opus export\n\nSee the site.", plainNotes(md))
    }

    @Test fun emptyNotesStayEmpty() = assertEquals("", plainNotes("  \n "))
}
