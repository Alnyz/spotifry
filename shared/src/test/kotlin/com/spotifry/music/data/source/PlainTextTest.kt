package com.spotifry.music.data.source

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTextTest {
    @Test fun decodesTheEntitiesTheirTextUses() {
        assertEquals("Tum Hi Ho (From \"Aashiqui 2\")", plainText("Tum Hi Ho (From &quot;Aashiqui 2&quot;)"))
        assertEquals("Love & Hits", plainText("Love &amp; Hits"))
        assertEquals("Don't", plainText("Don&#039;t"))
        assertEquals("Don't", plainText("Don&#x27;t"))
    }

    @Test fun dropsTagsAndLeavesUnknownEntitiesAlone() {
        assertEquals("Bold", plainText("<b>Bold</b>"))
        assertEquals("a &madeup; b", plainText("a &madeup; b"))
    }
}
