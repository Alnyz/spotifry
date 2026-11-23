package com.spotifry.music.ui

import com.spotifry.music.ui.components.initials
import org.junit.Assert.assertEquals
import org.junit.Test

class StationInitialsTest {
    @Test fun firstLettersOfTheFirstTwoWords() {
        assertEquals("RS", initials("Radio Sholawat"))
        assertEquals("DF", initials("Delta FM"))
        assertEquals("K9", initials("Kis 95.1 FM"))
        assertEquals("P", initials("prambors"))
        assertEquals("WF", initials("  Wijaya-FM 103.5 FM Surabaya"))
    }

    @Test fun nothingToShowWithoutLetters() {
        assertEquals("", initials(null))
        assertEquals("", initials("  --  "))
    }
}
