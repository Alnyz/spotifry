package com.spotifry.music.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CopyOfTest {
    private fun s(id: String, title: String = "Cynical", sec: Long = 200) = Song(id, title, "blink-182", sec, null)

    @Test fun takesAnotherUploadOfTheSameSong() {
        assertEquals("good", copyOf(s("dead"), listOf(s("dead"), s("other", "Cynical (Live)"), s("good", "cynical", 202)))?.id)
    }

    @Test fun refusesADifferentLengthOrTheSameId() {
        assertNull(copyOf(s("dead"), listOf(s("dead"), s("long", sec = 260))))
    }
}
