package com.spotifry.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NameLooksTest {
    @Test fun unknownStyleKeyIsClassic() {
        assertEquals(NameStyle.CLASSIC, NameStyle.of("comic"))
        assertEquals(NameStyle.CLASSIC, NameStyle.of(null))
        assertEquals(NameStyle.NEON, NameStyle.of("neon"))
    }

    @Test fun artistNamesRoundTrip() {
        val m = mapOf("radiohead" to NameLook(NameShow.TEXT, NameStyle.GOTHIC, "a.png", false), "x" to NameLook())
        assertEquals(m, decodeArtistNames(encodeArtistNames(m)))
    }

    @Test fun brokenLinesAreSkipped() = assertEquals(setOf("ok"), decodeArtistNames("ok\t\tblock\t\t1\nnot a line\n\t\t").keys)

    @Test fun playlistShowsWhatWasPickedLast() {
        assertEquals(Shown.Text(NameStyle.BRUSH), shownForPlaylist(NameLook(NameShow.TEXT, NameStyle.BRUSH, "l.png")))
        assertEquals(Shown.Own("l.png", true), shownForPlaylist(NameLook(NameShow.OWN, NameStyle.BRUSH, "l.png")))
        assertEquals(Shown.Text(NameStyle.CLASSIC), shownForPlaylist(NameLook(NameShow.OWN, logoFile = null)))
    }

    @Test fun artistDefaultIsOfficialWhenFound() {
        assertEquals(Shown.Official(true), shownForArtist(null, true, officialFound = true))
        assertEquals(Shown.Text(NameStyle.CLASSIC), shownForArtist(null, true, officialFound = false))
    }

    @Test fun userPickBeatsTheOfficialLogo() =
        assertEquals(Shown.Text(NameStyle.GOTHIC), shownForArtist(NameLook(NameShow.TEXT, NameStyle.GOTHIC), true, officialFound = true))

    @Test fun officialPickWithoutALogoFallsBackToTheStyle() =
        assertEquals(Shown.Text(NameStyle.BLOCK), shownForArtist(NameLook(NameShow.OFFICIAL, NameStyle.BLOCK), true, officialFound = false))

    @Test fun nonFavouriteAlwaysGetsTheDefault() =
        assertEquals(Shown.Official(true), shownForArtist(NameLook(NameShow.OWN, logoFile = "a.png"), favourite = false, officialFound = true))

    @Test fun settingsKeepArtistNamesAndTheKey() {
        val p = MemoryPrefs()
        Settings(p).apply { setArtistName("x", NameLook(NameShow.TEXT, NameStyle.PIXEL)); setFanartKey("  k1 ") }
        val again = Settings(p)
        assertEquals(NameStyle.PIXEL, again.artistNames.value["x"]!!.style)
        assertEquals("k1", again.fanartKey.value)
    }
}
