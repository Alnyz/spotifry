package com.spotifry.desktop

import com.spotifry.music.data.cropBox
import org.jetbrains.skia.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaylistCoverTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun theDefaultCoverIsLaidOutByHowManySongsThereAre() {
        assertEquals(emptyList<Int>(), mosaicRows(0))
        assertEquals(listOf(1), mosaicRows(1))
        assertEquals(listOf(1, 1), mosaicRows(2))
        assertEquals(listOf(1, 2), mosaicRows(3))
        assertEquals(listOf(2, 2), mosaicRows(4))
        assertEquals(listOf(2, 2), mosaicRows(30))
    }

    @Test fun aSavedCoverIsASquareJpegNoBiggerThan800AndNeverOverwritten() {
        val covers = PlaylistCovers(tmp.newFolder("covers"))
        val pic = Surface.makeRasterN32Premul(1600, 1200).makeImageSnapshot()
        val a = covers.save(7, pic, cropBox(1600, 1200, 1f, 0f, 0f))
        Thread.sleep(3)
        val b = covers.save(7, pic, cropBox(1600, 1200, 1f, 0f, 0f))
        val img = javax.imageio.ImageIO.read(covers.file(a))
        assertEquals(800, img.width); assertEquals(800, img.height)
        assert(a != b && covers.file(b) != null)
        covers.delete(a)
        assertNull(covers.file(a)); assertNull(covers.file(null))
    }
}
