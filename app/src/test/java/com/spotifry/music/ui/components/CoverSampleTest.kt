package com.spotifry.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverSampleTest {
    @Test fun smallCoversShrinkButStaySharp() {
        assertEquals(1, sampleFor(226, large = false))  // already small
        assertEquals(2, sampleFor(544, large = false))  // 272 px
        assertEquals(4, sampleFor(1280, large = false)) // 320 px
    }

    @Test fun largeCoversOnlyHalveTheBigFrames() {
        assertEquals(1, sampleFor(544, large = true))
        assertEquals(2, sampleFor(1280, large = true))
    }
}
