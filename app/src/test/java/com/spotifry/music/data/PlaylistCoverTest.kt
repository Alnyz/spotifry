package com.spotifry.music.data

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.db.androidMigration
import com.spotifry.music.ui.playlists.defaultCoverSongs
import com.spotifry.music.ui.playlists.fadeColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistCoverTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: AppDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun close() = db.close()

    private fun song(id: String) = SongEntity(id, id, "A", 100, null, null, null, null)

    @Test fun playlistsStartOnTheDefaultCover() = runBlocking {
        db.playlists().create("Mix")
        assertNull(db.playlists().coverOf(1))
    }

    @Test fun theUpgradeKeepsPlaylistsAndSongsAndLeavesCoversEmpty() = runBlocking {
        val id = db.playlists().create("Kept")
        db.songs().upsert(song("s")); db.playlists().addSong(id, "s")
        val sql = db.openHelper.writableDatabase
        // an older database has no cover column
        sql.execSQL("ALTER TABLE playlists DROP COLUMN cover")
        androidMigration(AppDatabase.ADD_PLAYLIST_COVER).migrate(sql)
        assertNull(db.playlists().coverOf(id))
        db.playlists().setCover(id, "1-5.jpg")
        assertEquals("1-5.jpg", db.playlists().coverOf(id))
        val kept = db.playlists().observePlaylists().first().single()
        assertEquals("Kept", kept.name); assertEquals(1, kept.songCount)
    }

    @Test fun aChosenCoverShowsInTheListOfPlaylists() = runBlocking {
        val id = db.playlists().create("Mix")
        db.playlists().setCover(id, "x.jpg")
        assertEquals("x.jpg", db.playlists().observePlaylists().first().single().cover)
        db.playlists().setCover(id, null)
        assertNull(db.playlists().observePlaylists().first().single().cover)
    }

    @Test fun theSquareWindowCentresOnAPictureAndStaysInsideIt() {
        assertEquals(CropBox(200, 0, 600), cropBox(1000, 600, 1f, 0f, 0f))
        // dragged all the way either way it stops at the picture's edge
        assertEquals(CropBox(0, 0, 600), cropBox(1000, 600, 1f, -3f, 0f))
        assertEquals(CropBox(400, 0, 600), cropBox(1000, 600, 1f, 9f, 0f))
        // zoomed in 2x the window covers half as much
        assertEquals(CropBox(350, 150, 300), cropBox(1000, 600, 2f, 0f, 0f))
        // zoom never goes below filling the window
        assertEquals(cropBox(1000, 600, 1f, 0f, 0f), cropBox(1000, 600, .2f, 0f, 0f))
    }

    @Test fun aCroppedCoverIsSquareAndNotBiggerThan800() {
        val pic = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        val out = pic.cropped(cropBox(1600, 1200, 1f, 0f, 0f))
        assertEquals(800, out.width); assertEquals(800, out.height)
        val small = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).let { it.cropped(cropBox(300, 200, 1f, 0f, 0f)) }
        assertEquals(200, small.width); assertEquals(200, small.height)
    }

    @Test fun coverFilesAreKeptUntilDeletedAndNeverOverwritten() {
        val covers = PlaylistCovers(tmp.newFolder("covers"))
        val pic = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val a = covers.save(7, pic)
        Thread.sleep(3)
        val b = covers.save(7, pic)
        assertTrue(a != b)
        assertNotNull(covers.file(a)); assertNotNull(covers.file(b))
        covers.delete(a)
        assertNull(covers.file(a)); assertNotNull(covers.file(b))
        // no cover chosen, or a file that is gone, is the default cover
        assertNull(covers.file(null)); assertNull(covers.file("nope.jpg"))
    }

    @Test fun theDefaultCoverUsesFourSongsOrJustTheFirst() {
        val songs = (1..6).map { song("s$it") }
        assertEquals(listOf("s1", "s2", "s3", "s4"), defaultCoverSongs(songs).map { it.id })
        assertEquals(listOf("s1", "s2", "s3"), defaultCoverSongs(songs.take(3)).map { it.id })
        assertTrue(defaultCoverSongs(emptyList()).isEmpty())
    }

    @Test fun theFadeFollowsTheCoverOnDarkAndLightPagesAndIsQuietUntilItLoads() {
        val red = Color(0xFF541919)
        val dark = Color(0xFF0C0D10); val light = Color(0xFFF4F5F7); val grey = Color(0xFF1C1F26)
        assertEquals(grey, fadeColor(null, dark, grey))
        assertTrue(fadeColor(red, dark, grey).red > dark.red)
        // a light page stays light, with a hint of the cover
        val soft = fadeColor(red, light, grey)
        assertTrue(soft.luminance() > .5f && soft.red > soft.green)
        assertFalse(fadeColor(red, dark, grey) == fadeColor(red, light, grey))
    }
}
