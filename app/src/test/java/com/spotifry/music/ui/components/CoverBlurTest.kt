package com.spotifry.music.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class CoverBlurTest {
    @Test fun aHardEdgeComesOutSoftAndSmall() {
        val half = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (x in 0 until 64) for (y in 0 until 64) half.setPixel(x, y, if (x < 32) Color.BLACK else Color.WHITE)
        val out = blurOf(half)
        assertEquals(32, out.width)
        // across the old edge the brightness now steps gently, never more than a fifth of the way at once
        val row = (0 until 32).map { Color.red(out.getPixel(it, 16)) }
        assertTrue(row.zipWithNext().all { (a, b) -> b - a in 0..60 })
        assertTrue(row.first() < 20 && row.last() > 235)
    }
}
