package com.spotifry.music.player

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SleepTimerTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun fadesOverTheLastTenSeconds() {
        assertEquals(1f, fadeVolume(60_000))
        assertEquals(.5f, fadeVolume(5_000))
        assertEquals(0f, fadeVolume(-1))
    }

    @Test fun countdownText() {
        assertEquals("28:41", clockText(28 * 60_000L + 41_000))
        assertEquals("1:00:00", clockText(3_600_000))
        assertEquals("0:01", clockText(1))
    }

    @Test fun startTurnsOffAndBackCleanly() {
        var clock = 1_000L
        val t = SleepTimer { clock }
        val p = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        t.attach(p)
        t.start(30)
        assertEquals(30 * 60_000L, t.remainingMs())
        clock += 60_000
        assertEquals(29 * 60_000L, t.remainingMs())

        t.startEndOfSong()
        assertEquals(SleepState.EndOfSong, t.state.value)
        assertTrue(p.pauseAtEndOfMediaItems)

        t.off()
        assertEquals(SleepState.Off, t.state.value)
        assertFalse(p.pauseAtEndOfMediaItems)
        assertEquals(1f, p.volume)
        t.detach(); p.release()
    }
}
