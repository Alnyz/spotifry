package com.spotifry.music.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.ListenEntity
import com.spotifry.music.data.db.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var app: SharedPreferences
    private lateinit var trims: SharedPreferences
    private lateinit var queue: RestoreQueue
    private lateinit var backups: Backups

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        app = context.getSharedPreferences("app-test", 0).also { it.edit().clear().commit() }
        trims = context.getSharedPreferences("trims-test", 0).also { it.edit().clear().commit() }
        val waiting = context.getSharedPreferences("waiting-test", 0).also { it.edit().clear().commit() }
        queue = RestoreQueue(waiting, db.songs(), {}, { false })
        backups = Backups(db.backup(), app, trims, queue, "1.0", { 42L })
    }
    @After fun close() = db.close()

    private fun song(id: String, savedAt: Long? = null) = SongEntity(id, "T $id", "A $id", 200, "http://c/$id", savedAt, null, null)
    private fun file(
        songs: List<SongEntity> = emptyList(), playlists: List<BackupPlaylist> = emptyList(),
        history: List<com.spotifry.music.data.db.HistoryEntity> = emptyList(), plays: List<com.spotifry.music.data.db.PlayCountEntity> = emptyList(),
        listens: List<ListenEntity> = emptyList(), settings: Map<String, Any> = emptyMap(), trims: Map<String, String> = emptyMap(),
    ) = BackupFile("1.0", 42, songs, playlists, history, plays, listens, settings, trims)
    private fun playlist(name: String, vararg ids: String, order: String? = null) = BackupPlaylist(name, 7, order, ids.map { BackupPlaylistSong(it, 9) })

    /** A phone with some of everything. */
    private suspend fun fill() {
        db.songs().upsert(song("a")); db.songs().upsert(song("b")); db.songs().upsert(song("local:5"))
        db.songs().markSaved("a", "/f/a", "/t/a", 100)
        val mix = db.playlists().create("Mix")
        db.playlists().addSong(mix, "b"); db.playlists().addSong(mix, "a"); db.playlists().addSong(mix, "local:5")
        db.playlists().create("Mix")
        db.history().record("a", 11); db.history().record("local:5", 12)
        db.plays().count("a", 13); db.plays().count("local:5", 14)
        db.listens().insert(ListenEntity(15, "a", 60)); db.listens().insert(ListenEntity(16, "gone", 30, skipped = true)); db.listens().insert(ListenEntity(17, "local:5", 30))
        PlaylistOrders(app).set(mix, PlaylistOrder.TITLE)
        trims.edit().putString("a", "1000,").putString("local:5", "2000,").commit()
        app.edit().putBoolean("eq_on", true).putInt("pitch", -2).putLong("big", Long.MAX_VALUE).putFloat("speed", 1.1f).putString("theme", "DARK")
            .putStringSet("not_suggested", setOf("x", "y")).putLong(Updates.LAST_CHECK, 5).putString(Updates.LATER, "9").putBoolean(FIRST_LAUNCH_SEEN, false).commit()
    }

    /** What must not change: every table, as read for a backup. */
    private suspend fun snapshot() = listOf(
        db.backup().songs().sortedBy { it.id }, db.backup().playlists(), db.backup().playlistSongs(), db.backup().history().sortedBy { it.songId },
        db.backup().plays().sortedBy { it.songId }, db.backup().listens().sortedBy { it.startedAt },
    )

    private fun zipped(b: BackupFile) = ByteArrayOutputStream().also { writeBackup(b, it) }.toByteArray()

    @Test fun roundTripThroughTheZipIsEqual() = runBlocking {
        fill()
        val b = backups.build()
        assertEquals(b, readBackup(ByteArrayInputStream(zipped(b))))
        assertEquals(1.1f, b.settings["speed"])
        assertEquals(Long.MAX_VALUE, b.settings["big"])
        assertEquals(setOf("x", "y"), b.settings["not_suggested"])
    }

    @Test fun buildLeavesOutPathsThisPhoneAndPhoneFiles() = runBlocking {
        fill()
        val b = backups.build()
        assertTrue(b.songs.all { it.filePath == null && it.thumbPath == null })
        assertEquals(mapOf("a" to 100L, "b" to null), b.songs.associate { it.id to it.savedAt })
        assertEquals(listOf("b", "a"), b.playlists[0].songs.map { it.songId })
        assertEquals("TITLE", b.playlists[0].order)
        assertEquals(listOf("a"), b.history.map { it.songId })
        assertEquals(listOf("a"), b.plays.map { it.songId })
        assertEquals(mapOf("a" to "1000,"), b.trims)
        assertFalse(b.settings.keys.any { it == Updates.LAST_CHECK || it == Updates.LATER || it == FIRST_LAUNCH_SEEN || it.startsWith(PlaylistOrders.KEY_PREFIX) })
        assertEquals(setOf(15L, 16L, 17L), b.listens.map { it.startedAt }.toSet()) // the totals keep every listen
    }

    @Test fun restoreOntoTheSamePhoneChangesNothing() = runBlocking {
        fill()
        val before = snapshot()
        val settings = app.all.toMap()
        val b = readBackup(ByteArrayInputStream(zipped(backups.build())))
        backups.restore(b)
        backups.restore(b)
        assertEquals(before, snapshot())
        assertEquals(settings + (FIRST_LAUNCH_SEEN to true), app.all.toMap())
        assertEquals(emptyList<String>(), queue.ids.value)
    }

    @Test fun restoreIntoAnEmptyPhone() = runBlocking {
        fill()
        val b = backups.build()
        val otherDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val otherApp = context.getSharedPreferences("other-app", 0).also { it.edit().clear().commit() }
        val otherTrims = context.getSharedPreferences("other-trims", 0).also { it.edit().clear().commit() }
        val otherQueue = RestoreQueue(context.getSharedPreferences("other-waiting", 0).also { it.edit().clear().commit() }, otherDb.songs(), {}, { false })
        val other = Backups(otherDb.backup(), otherApp, otherTrims, otherQueue, "1.0")
        other.restore(b)
        val twice = otherDb.backup().songs() to otherDb.backup().playlistSongs()

        assertEquals(listOf("a"), otherQueue.ids.value)
        assertEquals(setOf("a", "b"), otherDb.backup().songs().map { it.id }.toSet())
        assertNull(otherDb.songs().get("a")!!.filePath)
        assertEquals(emptyList<SongEntity>(), otherDb.songs().observeSaved().first()) // Saved lists only songs with a file
        val lists = otherDb.playlists().observePlaylists().first()
        assertEquals(listOf("Mix", "Mix"), lists.map { it.name }) // two with one name stay two
        assertEquals(listOf("b", "a"), otherDb.playlists().observeSongs(lists[0].id).first().map { it.id })
        assertEquals(0, lists[1].songCount)
        assertEquals("TITLE", otherApp.getString(PlaylistOrders.key(lists[0].id), null))
        assertEquals(-2, otherApp.getInt("pitch", 0))
        assertEquals(setOf("x", "y"), otherApp.getStringSet("not_suggested", null))
        assertTrue(otherApp.getBoolean(FIRST_LAUNCH_SEEN, false))
        assertFalse(otherApp.contains(Updates.LAST_CHECK))
        assertEquals("1000,", otherTrims.getString("a", null))
        assertEquals(setOf(15L, 16L, 17L), otherDb.backup().listens().map { it.startedAt }.toSet()) // "gone" has no song row and is kept

        other.restore(b)
        assertEquals(twice, otherDb.backup().songs() to otherDb.backup().playlistSongs())
        assertEquals(listOf("a"), otherQueue.ids.value)
        otherDb.close()
    }

    @Test fun mergeNeverOverwritesNewerData() = runBlocking {
        db.songs().upsert(song("a")); db.songs().upsert(song("mine"))
        db.songs().markSaved("a", "/f/a", null, 100)
        val mix = db.playlists().create("Mix")
        db.playlists().addSong(mix, "mine")
        db.plays().count("a", 50); db.plays().count("a", 50); db.plays().count("a", 50)
        db.history().record("a", 500)
        trims.edit().putString("a", "1000,").commit()

        backups.restore(file(
            songs = listOf(song("a", 1).copy(title = "Old title"), song("b", 2)),
            playlists = listOf(playlist("Mix", "mine", "b")),
            history = listOf(com.spotifry.music.data.db.HistoryEntity("a", 1), com.spotifry.music.data.db.HistoryEntity("b", 2)),
            plays = listOf(com.spotifry.music.data.db.PlayCountEntity("a", 2, 900), com.spotifry.music.data.db.PlayCountEntity("b", 4, 2)),
            trims = mapOf("a" to "9,", "b" to "8,"),
        ))

        val a = db.songs().get("a")!!
        assertEquals("T a", a.title)
        assertEquals("/f/a", a.filePath)
        assertEquals(100L, a.savedAt)
        assertEquals(listOf("b"), queue.ids.value) // "a" has its file
        assertEquals(1, db.playlists().observePlaylists().first().size)
        assertEquals(listOf("mine", "b"), db.playlists().observeSongs(mix).first().map { it.id })
        val plays = db.backup().plays().associateBy { it.songId }
        assertEquals(3, plays["a"]!!.count) // the larger, not the sum
        assertEquals(900L, plays["a"]!!.lastPlayedAt) // the later
        assertEquals(4, plays["b"]!!.count)
        assertEquals(500L, db.backup().history().first { it.songId == "a" }.playedAt)
        assertEquals("1000,", trims.getString("a", null))
        assertEquals("8,", trims.getString("b", null))
    }

    @Test fun settingsAreReplacedButThisPhonesAreKept() = runBlocking {
        app.edit().putBoolean("gone", true).putInt("pitch", 1).putLong(Updates.LAST_CHECK, 5).putString(PlaylistOrders.key(3), "TITLE").commit()
        backups.restore(file(settings = mapOf<String, Any>("pitch" to (2 as Any), Updates.LAST_CHECK to 999L)))
        assertFalse(app.contains("gone"))
        assertEquals(2, app.getInt("pitch", 0))
        assertEquals(5L, app.getLong(Updates.LAST_CHECK, 0))
        assertEquals("TITLE", app.getString(PlaylistOrders.key(3), null))
    }

    @Test fun savedSongsWaitOldestFirstSoSavedKeepsItsOrder() = runBlocking {
        backups.restore(file(songs = listOf(song("new", 30), song("no", null), song("old", 10), song("mid", 20))))
        assertEquals(listOf("old", "mid", "new"), queue.ids.value)
    }

    @Test fun aFailureMidRestoreRollsBackAndTouchesNoSettings() = runBlocking<Unit> {
        app.edit().putInt("pitch", 1).commit()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER boom BEFORE INSERT ON listens BEGIN SELECT RAISE(ABORT, 'boom'); END")
        val b = file(
            songs = listOf(song("a", 1)), playlists = listOf(playlist("Mix", "a")), listens = listOf(ListenEntity(1, "a", 5)),
            settings = mapOf("pitch" to 2), trims = mapOf("a" to "1,"),
        )
        runCatching { backups.restore(b) }.also { assertTrue(it.isFailure) }
        assertEquals(emptyList<SongEntity>(), db.backup().songs())
        assertEquals(0, db.backup().playlists().size)
        assertEquals(1, app.getInt("pitch", 0))
        assertFalse(trims.contains("a"))
        assertEquals(emptyList<String>(), queue.ids.value)
    }

    private fun zipOf(name: String, text: String) = ByteArrayOutputStream().also { o ->
        ZipOutputStream(o).use { it.putNextEntry(ZipEntry(name)); it.write(text.toByteArray()); it.closeEntry() }
    }.toByteArray()
    private fun refused(bytes: ByteArray) = assertThrows(BackupError::class.java) { readBackup(ByteArrayInputStream(bytes)) }.message!!

    @Test fun aNewerFormatIsRefused() {
        assertTrue(refused(zipOf("backup.json", """{"format":${BACKUP_FORMAT + 1}}""")).contains("newer Spotifry"))
    }

    @Test fun wrongOrDamagedFilesAreRefused() {
        val good = zipped(file(songs = listOf(song("a"))))
        listOf(
            ByteArray(0),
            "not a zip".toByteArray(),
            ByteArrayOutputStream().also { ZipOutputStream(it).close() }.toByteArray(),
            zipOf("other.txt", "{}"),
            zipOf("backup.json", "not json"),
            zipOf("backup.json", "{}"),
            zipOf("backup.json", """{"format":1,"songs":[{"title":"no id"}]}"""),
            zipOf("backup.json", """{"format":1,"songs":[{"id":"../../evil"}]}"""),
            good.copyOf(good.size / 2),
        ).forEach { refused(it) }
    }

    @Test fun unknownFieldsAreIgnoredAndMissingOnesGetDefaults() {
        val b = readBackup(ByteArrayInputStream(zipOf("backup.json", """{"format":1,"later":1,"songs":[{"id":"a","extra":2}],"settings":{"x":{"type":"new","value":1}}}""")))
        assertEquals(listOf("a"), b.songs.map { it.id })
        assertNull(b.songs[0].thumbnailUrl)
        assertEquals(emptyMap<String, Any>(), b.settings)
    }

    @Test fun summaryCounts() {
        assertEquals("1 playlist, 2 songs, 0 trims", file(songs = listOf(song("a"), song("b")), playlists = listOf(playlist("x"))).summary())
    }

    // the queue

    private fun queueOf(saveIt: suspend (String) -> Boolean, metered: () -> Boolean = { false }): RestoreQueue {
        val seen = context.getSharedPreferences("queue-test", 0).also { it.edit().clear().commit() }
        return RestoreQueue(seen, db.songs(), { s -> if (saveIt(s.id)) db.songs().markSaved(s.id, "/f/${s.id}", null, 1) }, metered)
    }
    private suspend fun rows(vararg ids: String) = ids.forEach { db.songs().upsert(song(it)) }

    @Test fun queueDownloadsOneAtATimeInOrderAndForgetsDoneOnes() = runBlocking {
        rows("a", "b", "c")
        val order = mutableListOf<String>()
        val q = queueOf({ order += it; true })
        q.add(listOf("a", "b", "c"))
        q.run(wifiOnly = true)
        assertEquals(listOf("a", "b", "c"), order)
        assertEquals(emptyList<String>(), q.ids.value)
        assertEquals(RestoreStatus.Idle, q.status.value)
    }

    @Test fun queueKeepsFailedSongsAndMovesOn() = runBlocking {
        rows("a", "b", "c")
        val q = queueOf({ it != "b" })
        q.add(listOf("a", "b", "c"))
        q.run(wifiOnly = false)
        assertEquals(listOf("b"), q.ids.value)
        assertEquals(RestoreStatus.Failed(1), q.status.value)
    }

    @Test fun queueStopsAfterThreeFailuresInARow() = runBlocking {
        rows("a", "b", "c", "d", "e")
        val tried = mutableListOf<String>()
        val q = queueOf({ tried += it; false })
        q.add(listOf("a", "b", "c", "d", "e"))
        q.run(wifiOnly = false)
        assertEquals(listOf("a", "b", "c"), tried)
        assertEquals(5, q.ids.value.size)
    }

    @Test fun queueWaitsForWifiOnlyWhenAsked() = runBlocking {
        rows("a")
        var tried = 0
        val q = queueOf({ tried++; true }, metered = { true })
        q.add(listOf("a"))
        q.run(wifiOnly = true)
        assertEquals(0, tried)
        assertEquals(RestoreStatus.NeedsWifi, q.status.value)
        q.run(wifiOnly = false)
        assertEquals(1, tried)
    }

    @Test fun queueSkipsSongsSavedByHandAndSurvivesARestart() = runBlocking {
        rows("a", "b")
        db.songs().markSaved("a", "/f/a", null, 1)
        val q = queueOf({ false })
        q.add(listOf("a", "b"))
        val again = RestoreQueue(context.getSharedPreferences("queue-test", 0), db.songs(), {}, { false })
        assertEquals(listOf("a", "b"), again.ids.value)
        q.run(wifiOnly = false)
        assertEquals(listOf("b"), q.ids.value)
        q.forget()
        assertEquals(emptyList<String>(), q.ids.value)
    }

    @Test fun fileNameHasTheDate() = assertEquals("spotifry-backup-2026-11-06.zip", backupFileName(java.time.LocalDate.of(2026, 11, 6)))
}
