package com.spotifry.desktop

import com.spotifry.music.player.EqShape
import org.junit.Test
import org.junit.Assert.assertEquals

class EqualizerPageTest {
    @Test fun bandNamesFollowWhereEachBandSits() {
        assertEquals(listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"), EqShape.DEFAULT.hz.map(::hzLabel))
        assertEquals("1.5k", hzLabel(1_480.0))
        assertEquals("999", hzLabel(999.4))
    }
}
