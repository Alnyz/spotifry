package com.spotifry.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedPitchLabelTest {
    @Test fun normalShowsNothing() = assertNull(speedPitchLabel(1f, 0))

    @Test fun showsWhatChanged() {
        assertEquals("1.25×", speedPitchLabel(1.25f, 0))
        assertEquals("0.5×", speedPitchLabel(.5f, 0))
        assertEquals("2×", speedPitchLabel(2f, 0))
        assertEquals("+2", speedPitchLabel(1f, 2))
        assertEquals("0.75× · −1", speedPitchLabel(.75f, -1))
    }
}
