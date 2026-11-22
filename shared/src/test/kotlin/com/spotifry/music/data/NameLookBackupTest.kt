package com.spotifry.music.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.AppDatabase_Impl
import com.spotifry.music.data.db.look
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NameLookBackupTest {
    private val open = mutableListOf<AppDatabase>()
    @After fun close() = open.forEach { it.close() }

    private class Device(val db: AppDatabase, val app: MemoryPrefs, val backups: Backups, val playlistLogos: File, val artistLogos: File)

    private fun device(): Device {
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>(factory = { AppDatabase_Impl() })
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build().also(open::add)
        val app = MemoryPrefs()
        val root = Files.createTempDirectory("names").toFile().also { it.deleteOnExit() }
        val pl = File(root, "playlist_logos"); val ar = File(root, "artist_logos")
        val queue = RestoreQueue(MemoryPrefs(), db.songs(), save = {}, metered = { false })
        return Device(db, app, Backups(db.backup(), app, MemoryPrefs(), queue, "test", now = { 5L }, playlistLogos = pl, artistLogos = ar), pl, ar)
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    private fun zip(d: Device) = ByteArrayOutputStream().also { writeBackup(runBlocking { d.backups.build() }, it) }.toByteArray()

    @Test fun nameLooksAndLogosRoundTrip() = runBlocking {
        val a = device()
        val id = a.db.playlists().create("Road trip")
        a.playlistLogos.mkdirs(); File(a.playlistLogos, "p.png").writeBytes(png)
        a.db.playlists().setNameStyle(id, "pixel"); a.db.playlists().setNameLogo(id, "p.png"); a.db.playlists().setLogoTint(id, false)
        a.artistLogos.mkdirs(); File(a.artistLogos, "a1.png").writeBytes(png)
        Settings(a.app).setArtistName("radiohead", NameLook(NameShow.OWN, NameStyle.GOTHIC, "a1.png", true))

        val b = device()
        b.backups.restore(readBackup(ByteArrayInputStream(zip(a))))
        val p = b.db.backup().playlists().single()
        val look = p.look()
        assertEquals(NameShow.OWN, look.show); assertEquals(NameStyle.PIXEL, look.style); assertFalse(look.tint)
        assertArrayEquals(png, File(b.playlistLogos, look.logoFile!!).readBytes())
        assertArrayEquals(png, File(b.artistLogos, "a1.png").readBytes())
        assertEquals("a1.png", Settings(b.app).artistNames.value["radiohead"]!!.logoFile)
    }

    @Test fun oldBackupWithoutNameFieldsGetsDefaults() {
        val p = decodeBackup("""{"format":1,"playlists":[{"name":"x","createdAt":1,"songs":[]}]}""").playlists.single()
        assertNull(p.nameStyle); assertNull(p.nameShow); assertTrue(p.logoTint); assertNull(p.nameLogo)
    }

    @Test fun notAPngIsLeftOut() = runBlocking {
        val a = device()
        val id = a.db.playlists().create("x")
        a.playlistLogos.mkdirs(); File(a.playlistLogos, "p.png").writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0))
        a.db.playlists().setNameLogo(id, "p.png")
        assertNull(a.backups.build().playlists.single().nameLogo)
    }

    @Test fun artistLogoNameWithSlashIsDropped() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("backup.json"))
            z.write("""{"format":1,"artistLogos":["../x.png","ok.png"]}""".toByteArray()); z.closeEntry()
            listOf("artist_logos/../x.png", "artist_logos/ok.png").forEach { z.putNextEntry(ZipEntry(it)); z.write(png); z.closeEntry() }
        }
        val b = readBackup(ByteArrayInputStream(out.toByteArray()))
        assertEquals(setOf("ok.png"), b.artistLogos.keys)
        val d = device()
        d.backups.restore(b)
        assertFalse(File(d.artistLogos.parentFile, "x.png").exists())
        assertTrue(File(d.artistLogos, "ok.png").exists())
    }
}
