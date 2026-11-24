package com.spotifry.music.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReorderTest {
    @Test fun movesOnePlaceEitherWay() {
        assertEquals(listOf("b", "a", "c"), listOf("a", "b", "c").moved(0, 1))
        assertEquals(listOf("a", "c", "b"), listOf("a", "b", "c").moved(2, 1))
    }

    @Test fun movesAcrossTheList() {
        assertEquals(listOf("b", "c", "a"), listOf("a", "b", "c").moved(0, 2))
    }
}
