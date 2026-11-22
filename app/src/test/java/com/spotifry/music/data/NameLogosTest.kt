package com.spotifry.music.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NameLogosTest {
    @get:Rule val tmp = TemporaryFolder()
    private val files get() = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private fun logos() = NameLogos(File(tmp.root, "logos"))
    private val pngStart = listOf(0x89, 0x50, 0x4E, 0x47).map { it.toByte() }

    @Test fun svgImportGivesAPng() = runBlocking {
        val svg = tmp.newFile("logo.svg").apply { writeText("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 80"><rect width="200" height="80"/></svg>""") }
        val l = logos()
        val name = l.import(files, Uri.fromFile(svg))!!.name
        val f = l.file(name)!!
        assertEquals(pngStart, f.readBytes().take(4))
        assertEquals(1200, BitmapFactory.decodeFile(f.path).width)
    }

    @Test fun wideImageIsScaledTo1200() = runBlocking {
        val png = tmp.newFile("wide.png")
        png.outputStream().use { Bitmap.createBitmap(2000, 400, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val l = logos()
        val f = l.file(l.import(files, Uri.fromFile(png))!!.name)!!
        assertEquals(pngStart, f.readBytes().take(4))
        assertEquals(1200, BitmapFactory.decodeFile(f.path).width)
    }

    @Test fun tallSvgIsCapped() = runBlocking {
        val svg = tmp.newFile("tall.svg").apply { writeText("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 400"><rect width="100" height="400"/></svg>""") }
        val l = logos()
        val bmp = BitmapFactory.decodeFile(l.file(l.import(files, Uri.fromFile(svg))!!.name)!!.path)
        assertTrue(bmp.height <= NameLogos.MAX_HEIGHT)
    }

    @Test fun photoWithoutSeeThroughPartsIsMarked() = runBlocking {
        val photo = tmp.newFile("photo.png")
        photo.outputStream().use { Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val cutout = tmp.newFile("cutout.png")
        cutout.outputStream().use { Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { setPixel(5, 5, android.graphics.Color.RED) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val l = logos()
        assertEquals(false, l.import(files, Uri.fromFile(photo))!!.seeThrough)
        assertEquals(true, l.import(files, Uri.fromFile(cutout))!!.seeThrough)
    }

    @Test fun unreadableFileGivesNull() = runBlocking {
        assertNull(logos().import(files, Uri.fromFile(tmp.newFile("x.png").apply { writeText("not a picture") })))
    }
}
