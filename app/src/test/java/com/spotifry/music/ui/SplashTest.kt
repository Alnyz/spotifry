package com.spotifry.music.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashTest {
    @Test fun playsOnAColdStartOnly() {
        assertTrue(shouldPlaySplash(fresh = true, playedBefore = false, animationsOff = false))
        assertFalse("turned, recreated or restored: saved state", shouldPlaySplash(fresh = false, playedBefore = false, animationsOff = false))
        assertFalse("back to a living app", shouldPlaySplash(fresh = true, playedBefore = true, animationsOff = false))
    }

    @Test fun withAnimationsOffThereIsNoSplash() {
        assertFalse(shouldPlaySplash(fresh = true, playedBefore = false, animationsOff = true))
    }

    @Test fun theTimelineHitsTheMockupsMarks() {
        assertEquals(0f, turn(60f), 0f)
        assertEquals(1f, turn(520f), 0f)
        assertEquals(1f, discShrink(330f), 0f)
        // top bar first, the others 45 ms apart, 500 ms each
        assertEquals(0f, flight(0, 520f), 0f)
        assertEquals(1f, flight(0, 1020f), 0f)
        assertEquals(0f, flight(4, 700f), 0f)
        assertEquals(1f, flight(4, 1200f), 0f)
        assertTrue("standard easing is past half way at half time", flight(0, 770f) > .5f)
        // the greeting is written 1.02 to 1.30 s
        assertEquals(0f, write(0, 1020f), 0f)
        assertEquals(1f, write(0, 1300f), 0f)
        assertEquals(1f, pageIn(1410f, SplashPage.HOME), 0f)
        assertEquals(0f, pageIn(1110f, SplashPage.WELCOME), 0f)
        // ours ends at 1.41 s, then Home's own 250 ms fade
        assertEquals(1f, endFade(1410f), 0f)
        assertEquals(0f, endFade(1660f), 0f)
        assertEquals(1660f, SplashTime.END, 0f)
        assertTrue(SplashTime.CAP_MS > SplashTime.END)
    }

    @Test fun theFallbackIsAShortFadeFromTheDecision() {
        assertEquals(0f, fallback(520f), 0f)
        assertEquals(1f, fallback(820f), 0f)
    }

    @Test fun barsFlyOnlyToAKnownPageWithItsMainPartsInPlace() {
        assertTrue(fliesTo(SplashPage.HOME, setOf("greeting", "hero", "tab")))
        assertTrue("the shortcut row and song cards are optional", fliesTo(SplashPage.HOME, setOf("greeting", "hero", "tab", "card0")))
        assertFalse("not placed by the decision", fliesTo(SplashPage.HOME, setOf("greeting", "tab")))
        assertFalse("another tab, or something over Home", fliesTo(null, setOf("greeting", "hero", "tab")))
        assertTrue(fliesTo(SplashPage.WELCOME, setOf("welcome", "fresh")))
        assertFalse("Home's places don't make a Welcome landing", fliesTo(SplashPage.WELCOME, setOf("greeting", "hero", "tab")))
    }

    @Test fun aSplitStartsAsEqualSharesAndEndsOnTheRealPieces() {
        val real = listOf(Rect(20f, 0f, 130f, 56f), Rect(140f, 0f, 250f, 56f), Rect(260f, 0f, 370f, 56f))
        val whole = union(real)
        assertEquals(Rect(20f, 0f, 370f, 56f), whole)
        val start = pieces(whole, real, 0f)
        assertEquals(350f / 3, start[0].width, .01f)
        assertEquals(whole.right, start[2].right, .01f)
        assertEquals(real, pieces(whole, real, 1f))
    }
}
