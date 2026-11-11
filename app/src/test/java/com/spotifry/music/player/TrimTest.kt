package com.spotifry.music.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.source.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrimTest {
    private val len = 232_000L // 3:52

    @Test fun keepsAtLeastTenSeconds() {
        val t = Trim(15_000, 220_000)
        assertEquals(210_000L, t.withStart(215_000, len).startMs)
        assertEquals(0L, t.withStart(-5_000, len).startMs)
        assertEquals(25_000L, t.withEnd(1_000, len).endMs)
        assertEquals(205_000L, t.keptMs(len))
    }

    @Test fun endAtTheSongsEndIsNoEnd() {
        assertNull(Trim(15_000, 220_000).withEnd(240_000, len).endMs)
        assertTrue(Trim(0, 220_000).withEnd(len, len).isWhole)
    }

    @Test fun menuOnlyOffersPointsThatLeaveTenSeconds() {
        assertTrue(canStartAt(15_000, len))
        assertFalse(canStartAt(225_000, len))
        assertTrue(canStartAt(225_000, 0)) // length not known yet
        assertFalse(canEndAt(5_000))
        assertTrue(canEndAt(220_000))
    }

    @Test fun timeText() {
        assertEquals("0:15", clipText(15_999))
        assertEquals("3:40", clipText(220_000))
        assertEquals("1:02:05", clipText(3_725_000))
        assertEquals("plays 0:15 to 3:52", Trim(15_000).note(len))
    }

    @Test fun keptOnThePhoneAndOnTheItem() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("trims_test", Context.MODE_PRIVATE)
        val trims = Trims(prefs)
        trims["a"] = Trim(15_000, 220_000)
        trims["b"] = Trim(15_000)
        assertEquals(Trim(15_000, 220_000), Trims(prefs)["a"])
        assertEquals(Trim(15_000, null), Trims(prefs)["b"])
        trims["a"] = Trim()
        assertNull(Trims(prefs)["a"])

        val song = Song("b", "t", "a", 232, null, null)
        assertEquals(Trim(15_000, 220_000), song.toMediaItem(Trim(15_000, 220_000)).trim)
        assertNull(song.toMediaItem(null).trim)
    }

    @Test fun aDifferentCutIsADifferentAddressSoThePlayerReallyReplacesTheItem() {
        val song = Song("abc", "T", "A", 230, null)
        val whole = song.toMediaItem(null).localConfiguration!!.uri
        val cut = song.toMediaItem(Trim(3_000, null)).localConfiguration!!.uri
        val cutEnd = song.toMediaItem(Trim(3_000, 220_000)).localConfiguration!!.uri
        assertTrue(whole != cut && cut != cutEnd && whole != cutEnd)
        assertEquals(whole, song.toMediaItem(Trim(0, null)).localConfiguration!!.uri)
        assertEquals("T", songFromUri("abc", cut).title)
    }
}
