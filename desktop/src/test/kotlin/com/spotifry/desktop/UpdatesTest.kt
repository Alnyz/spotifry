package com.spotifry.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatesTest {
    private fun release(tag: String, vararg assets: String, draft: Boolean = false) =
        """{"tag_name":"$tag","html_url":"https://github.com/x/releases/tag/$tag","draft":$draft,"prerelease":false,"assets":[${assets.joinToString(",") { "{\"name\":\"$it\"}" }}]}"""

    @Test fun phoneReleasesAreSkipped() {
        val json = "[" + listOf(release("v0.14.0", "spotifry-0.14.0.apk"), release("desktop-v0.3.0", "Spotifry-0.3.0-x86_64.AppImage"), release("desktop-v0.2.0", "Spotifry-0.2.0-x86_64.AppImage")).joinToString(",") + "]"
        assertEquals(DesktopRelease("0.3.0", "https://github.com/x/releases/tag/desktop-v0.3.0"), newestDesktopRelease(json))
    }

    @Test fun draftsDontCountAndNoAppImageIsNothing() {
        assertNull(newestDesktopRelease("[" + release("desktop-v0.4.0", "Spotifry-0.4.0-x86_64.AppImage", draft = true) + "," + release("v0.14.0", "a.apk") + "]"))
    }

    @Test fun eachSystemLooksForItsOwnFile() {
        val json = "[" + release("desktop-v0.7.0", "Spotifry-0.7.0-windows-x64.msi") + "," + release("desktop-v0.6.0", "Spotifry-0.6.0-x86_64.AppImage") + "]"
        assertEquals("0.7.0", newestDesktopRelease(json, windows = true)!!.version)
        assertEquals("0.6.0", newestDesktopRelease(json, windows = false)!!.version)
    }

    @Test fun theNewestWinsWhateverTheOrder() {
        val json = "[" + release("d2", "Spotifry-0.2.0-x86_64.AppImage") + "," + release("d10", "Spotifry-0.10.0-x86_64.AppImage") + "]"
        assertEquals("0.10.0", newestDesktopRelease(json)!!.version)
    }
}
