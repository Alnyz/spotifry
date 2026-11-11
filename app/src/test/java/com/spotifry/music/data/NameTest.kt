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

    @Test fun invisibleFormatCharactersGoAndLeaveAnEmptyName() {
        assertEquals("", cleanName("\u200B\u200F\u2060\uFEFF\u202E\u2066\u2069 "))
        assertEquals("Ali", cleanName("A\u200Bl\u202Ei\uFEFF"))
    }

    @Test fun otherScriptsAreKept() = assertEquals("علي", cleanName("علي"))

    @Test fun aNoteIsCleanedLikeANameButLonger() {
        assertEquals("loved it at the beach", cleanNote("  loved it\n at the\tbeach \u200B"))
        assertEquals("ab".repeat(70), cleanNote("ab".repeat(100)))
        assertEquals("👨‍👩‍👧".repeat(NOTE_MAX), cleanNote("👨‍👩‍👧".repeat(NOTE_MAX + 5)))
        assertEquals("", cleanNote(" \n "))
    }
}
