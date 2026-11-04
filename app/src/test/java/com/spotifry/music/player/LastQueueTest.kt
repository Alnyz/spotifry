package com.spotifry.music.player

import com.spotifry.music.data.source.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LastQueueTest {
    @Test fun survivesTheRoundTrip() {
        val q = LastQueue(
            listOf(Song("a", "One", "X", 200, "https://i/a.jpg", "/files/a.jpg"), Song("b", "Two", "Y", 180, null)),
            1, 42_000, shuffle = true, repeatMode = 2, from = "chill",
        )
        assertEquals(q, LastQueue.decode(q.encode()))
        assertEquals(null, LastQueue.decode(q.copy(from = null).encode())!!.from)
    }

    @Test fun brokenOrEmptyMeansFreshStart() {
        assertNull(LastQueue.decode("not json"))
        assertNull(LastQueue.decode(LastQueue(emptyList(), 0, 0, false, 0, null).encode()))
    }
}
