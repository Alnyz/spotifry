package com.spotifry.desktop

import com.spotifry.music.player.SleepState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    private class FakePlayer : SleepTimer.Target {
        var paused = false
        var gain = 1f
        var left: Long? = 60_000
        override fun pause() { paused = true }
        override fun fade(gain: Float) { this.gain = gain }
        override var pauseAtEnd: (() -> Unit)? = null
        override fun songLeftMs() = left
    }

    @Test fun minutesFadeOverTheLastTenSecondsThenPause() {
        val scope = TestScope()
        val p = FakePlayer()
        val timer = SleepTimer(scope, p) { scope.testScheduler.currentTime }
        timer.start(1)
        scope.runCurrent()
        assertEquals(1f, p.gain)
        scope.advanceTimeBy(55_000); scope.runCurrent()
        assertEquals(.5f, p.gain, .05f)
        assertFalse(p.paused)
        assertEquals(5_000.0, timer.remainingMs()!!.toDouble(), 300.0)
        scope.advanceTimeBy(5_300); scope.runCurrent()
        assertTrue(p.paused)
        assertEquals(SleepState.Off, timer.state.value)
        assertEquals(1f, p.gain) // the next play is at full volume
    }

    @Test fun endOfSongFadesWithTheSongAndStopsWhenItEnds() {
        val scope = TestScope()
        val p = FakePlayer()
        val timer = SleepTimer(scope, p) { scope.testScheduler.currentTime }
        timer.startEndOfSong()
        p.left = 2_500; scope.runCurrent()
        assertEquals(.25f, p.gain, .01f)
        p.pauseAtEnd!!.invoke() // the player reached the end
        assertEquals(SleepState.Off, timer.state.value)
        assertNull(p.pauseAtEnd)
        assertEquals(1f, p.gain)
    }

    @Test fun anotherChoiceStartsOverAndOffUndoesTheFade() {
        val scope = TestScope()
        val p = FakePlayer()
        val timer = SleepTimer(scope, p) { scope.testScheduler.currentTime }
        timer.startEndOfSong()
        timer.start(15)
        assertNull(p.pauseAtEnd)
        assertEquals(15 * 60_000L, timer.remainingMs())
        scope.advanceTimeBy(15 * 60_000L - 2_000); scope.runCurrent()
        assertTrue(p.gain < .3f)
        timer.off(); scope.runCurrent()
        assertEquals(1f, p.gain)
        scope.advanceTimeBy(10_000); scope.runCurrent()
        assertFalse(p.paused)
    }
}
