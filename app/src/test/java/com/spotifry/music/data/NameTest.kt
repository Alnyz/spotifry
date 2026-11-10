package com.spotifry.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NameTest {
    @Test fun tidiesSpacesAndBreaks() {
        assertEquals("Ali Saefudin", cleanName("  Ali \n\t Saefudin  "))
        assertEquals("", cleanName("   \n "))
    }

    @Test fun invisibleControlCharactersGo() = assertEquals("Ali", cleanName("A\u0000l\u0007i"))

    @Test fun cutsAtTwentyAsAPersonCountsAndNeverSplitsAnEmoji() {
        assertEquals("Alexandria Kristofer", cleanName("Alexandria Kristofer Jones"))
        val ten = "👨‍👩‍👧".repeat(25)
        assertEquals("👨‍👩‍👧".repeat(20), cleanName(ten))
        val accented = "e\u0301".repeat(30)
        assertEquals("e\u0301".repeat(20), cleanName(accented))
    }

    @Test fun otherScriptsAreKept() = assertEquals("علي", cleanName("علي"))
}
