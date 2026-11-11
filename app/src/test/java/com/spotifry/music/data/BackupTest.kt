package com.spotifry.music.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.ListenEntity
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.db.SongNoteEntity
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
        db.notes().put(SongNoteEntity("a", "first dance", 18)); db.notes().put(SongNoteEntity("local:5", "on this phone only", 19))
        PlaylistOrders(app).set(mix, PlaylistOrder.TITLE)
        trims.edit().putString("a", "1000,").putString("local:5", "2000,").commit()
        app.edit().putBoolean("eq_on", true).putInt("pitch", -2).putLong("big", Long.MAX_VALUE).putFloat("speed", 1.1f).putString("theme", "DARK")
            .putStringSet("not_suggested", setOf("x", "y")).putLong(Updates.LAST_CHECK, 5).putString(Updates.LATER, "9").putBoolean(FIRST_LAUNCH_SEEN, false).commit()
    }

    /** What must not change: every table, as read for a backup. */
    private suspend fun snapshot() = listOf(
        db.backup().songs().sortedBy { it.id }, db.backup().playlists(), db.backup().playlistSongs(), db.backup().history().sortedBy { it.songId },
        db.backup().plays().sortedBy { it.songId }, db.backup().listens().sortedBy { it.startedAt }, db.backup().notes().sortedBy { it.songId },
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
        assertEquals(listOf(SongNoteEntity("a", "first dance", 18)), b.notes)
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
        assertEquals(listOf(SongNoteEntity("a", "first dance", 18)), otherDb.backup().notes())

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

    @Test fun theNewerNoteWinsAndOthersAreAdded() = runBlocking {
        db.notes().put(SongNoteEntity("a", "mine, newer", 50)); db.notes().put(SongNoteEntity("b", "mine, older", 50))
        backups.restore(file().copy(notes = listOf(SongNoteEntity("a", "old", 10), SongNoteEntity("b", "newer", 60), SongNoteEntity("c", "new", 5))))
        assertEquals(
            listOf(SongNoteEntity("a", "mine, newer", 50), SongNoteEntity("b", "newer", 60), SongNoteEntity("c", "new", 5)),
            db.backup().notes().sortedBy { it.songId },
        )
    }

    @Test fun notesFromAFileAreCleanedAndEmptyOnesDropped() {
        val long = "x".repeat(200)
        val b = readBackup(ByteArrayInputStream(zipOf("backup.json", """{"format":1,"notes":[{"songId":"a","text":"  two\nlines ","editedAt":3},{"songId":"b","text":" \u200B ","editedAt":4},{"songId":"c","text":"$long","editedAt":5}]}""")))
        assertEquals(listOf(SongNoteEntity("a", "two lines", 3), SongNoteEntity("c", "x".repeat(NOTE_MAX), 5)), b.notes)
    }

    @Test fun settingsAreReplacedButThisPhonesAreKept() = runBlocking {
        app.edit().putBoolean("gone", true).putInt("pitch", 1).putLong(Updates.LAST_CHECK, 5).putString(PlaylistOrders.key(3), "TITLE").commit()
        backups.restore(file(settings = mapOf<String, Any>("pitch" to (2 as Any), Updates.LAST_CHECK to 999L)))
        assertFalse(app.contains("gone"))
        assertEquals(2, app.getInt("pitch", 0))
        assertEquals(5L, app.getLong(Updates.LAST_CHECK, 0))
        assertEquals("TITLE", app.getString(PlaylistOrders.key(3), null))
    }

    @Test fun theNameGoesIntoTheBackupAndComesBack() = runBlocking {
        app.edit().putString(Settings.NAME, "Ali").commit()
        val b = readBackup(ByteArrayInputStream(zipped(backups.build())))
        assertEquals("Ali", b.settings[Settings.NAME])
        app.edit().clear().commit()
        backups.restore(b)
        assertEquals("Ali", app.getString(Settings.NAME, null))
    }

    @Test fun aBackupWithoutANameKeepsThisPhonesName() = runBlocking {
        app.edit().putString(Settings.NAME, "Ali").commit()
        backups.restore(file(settings = mapOf<String, Any>("pitch" to (2 as Any))))
        assertEquals("Ali", app.getString(Settings.NAME, null))
        assertEquals(2, app.getInt("pitch", 0))
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

    @Test fun aWrongTypedSettingIsSkippedAndTheAppStillOpens() = runBlocking {
        app.edit().putBoolean("data_saver", true).putInt("pitch", 1).commit()
        val b = file(settings = mapOf<String, Any>("data_saver" to "x", "speed" to "fast", "pitch" to (2 as Any)))
        assertEquals(setOf("data_saver", "speed"), backups.unusableSettings(b))
        assertEquals(2, backups.restore(b))
        assertEquals(true, app.getBoolean("data_saver", false)) // this phone's value stays
        assertFalse(app.contains("speed"))
        assertEquals(2, app.getInt("pitch", 0)) // the good ones still apply
        val s = Settings(app) // threw ClassCastException before
        assertEquals(true, s.dataSaver.value)
        assertEquals(1f, s.speed.value)
    }

    @Test fun onAFreshPhoneTheTableStillCatchesAWrongType() = runBlocking {
        val b = file(settings = mapOf<String, Any>("data_saver" to "x"))
        assertEquals(1, backups.restore(b))
        assertFalse(app.contains("data_saver"))
    }

    @Test fun aKeyNotInTheTableIsCheckedAgainstThisPhone() = runBlocking {
        app.edit().putInt("future", 1).commit()
        assertEquals(setOf("future"), backups.unusableSettings(file(settings = mapOf<String, Any>("future" to "x"))))
        assertEquals(emptySet<String>(), backups.unusableSettings(file(settings = mapOf<String, Any>("future" to (2 as Any), "other" to "y"))))
    }

    @Test fun everySettingTheAppWritesIsInTheTableWithItsRealType() {
        val s = Settings(app)
        s.setDataSaver(true); s.setSaveLow(true); s.setKeepScreenOn(true); s.setTheme(ThemeMode.DARK); s.setSameLoudness(true); s.setSkipSilence(true)
        s.setSmartTrim(true); s.setSpeed(1.5f); s.setPitch(1); s.setFadeSeconds(2); s.setEqOn(true); s.setEq("Flat", List(com.spotifry.music.player.EQ_BANDS) { 0 })
        s.saveEqBefore(); s.setAccent("EMBER", 1); s.setPureBlack(true); s.setJioSaavn(true); s.setArtists(listOf(PickedArtist("a", "", null)))
        s.markFirstLaunchSeen()
        app.edit().commit()
        val written = app.all.filterKeys { it != FIRST_LAUNCH_SEEN }
        assertEquals(19, written.size)
        written.forEach { (k, v) -> assertEquals(k, prefType(v!!), SETTING_TYPES[k]) }
    }

    @Test fun anExistingPlaylistKeepsItsOrderAndANewOneGetsTheBackups() = runBlocking {
        val mine = db.playlists().create("Mine")
        PlaylistOrders(app).set(mine, PlaylistOrder.ARTIST)
        val alsoMine = db.playlists().create("No order here")
        app.edit().commit()
        backups.restore(file(playlists = listOf(playlist("Mine", order = "TITLE"), playlist("No order here", order = "TITLE"), playlist("New", order = "RECENT"))))
        val new = db.backup().playlists().first { it.name == "New" }.id
        assertEquals("ARTIST", app.getString(PlaylistOrders.key(mine), null))
        assertNull(app.getString(PlaylistOrders.key(alsoMine), null))
        assertEquals("RECENT", app.getString(PlaylistOrders.key(new), null))
    }

    private fun proxied(real: SharedPreferences, edit: () -> SharedPreferences.Editor) = java.lang.reflect.Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(SharedPreferences::class.java),
    ) { _, m, args -> if (m.name == "edit") edit() else m.invoke(real, *args.orEmpty()) } as SharedPreferences

    private fun failingEdits(real: SharedPreferences): SharedPreferences = proxied(real) { throw java.io.IOException("disk full") }
    /** Edits that go through but whose commit reports false, as Android does when the write fails. */
    private fun failingCommits(real: SharedPreferences): SharedPreferences = proxied(real) {
        java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { self, m, _ -> if (m.name == "commit") false else self } as SharedPreferences.Editor
    }

    private fun vmFor(b: Backups, onRestart: () -> Unit = {}) = com.spotifry.music.ui.settings.BackupViewModel(b, { null }, { null }, onRestart)
    private suspend fun noteAfter(vm: com.spotifry.music.ui.settings.BackupViewModel) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        kotlinx.coroutines.withTimeout(5_000) { vm.step.first { it is com.spotifry.music.ui.settings.BackupStep.Note } } as com.spotifry.music.ui.settings.BackupStep.Note
    }

    @Test fun aFailureAfterTheMergeKeepsTheLibraryAndSaysSo() = runBlocking {
        var restarts = 0
        val vm = vmFor(Backups(db.backup(), app, failingEdits(trims), queue, "1.0")) { restarts++ }
        vm.restore(file(songs = listOf(song("a")), settings = mapOf<String, Any>("pitch" to (2 as Any)), trims = mapOf("a" to "1,")))
        val note = noteAfter(vm)
        assertEquals("Restored only in part", note.title)
        assertTrue(note.text.contains("were added"))
        assertEquals(listOf("a"), db.backup().songs().map { it.id })
        assertFalse(app.contains("pitch"))
        assertEquals(0, restarts)
    }

    @Test fun aCommitThatReportsFalseIsAFailureToo() = runBlocking {
        val b = file(songs = listOf(song("a")), trims = mapOf("a" to "1,"))
        assertThrows(BackupPartlyApplied::class.java) { runBlocking { Backups(db.backup(), failingCommits(app), trims, queue, "1.0").restore(b) } }
        assertThrows(BackupPartlyApplied::class.java) { runBlocking { Backups(db.backup(), app, failingCommits(trims), queue, "1.0").restore(b) } }
        Unit
    }

    @Test fun aFailureBeforeTheMergeCommitsSaysNothingWasAdded() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER boom BEFORE INSERT ON listens BEGIN SELECT RAISE(ABORT, 'boom'); END")
        var restarts = 0
        val vm = vmFor(backups) { restarts++ }
        vm.restore(file(songs = listOf(song("a")), listens = listOf(ListenEntity(1, "a", 5))))
        val note = noteAfter(vm)
        assertEquals("Couldn't restore", note.title)
        assertTrue(note.text.contains("nothing was added"))
        assertEquals(emptyList<SongEntity>(), db.backup().songs())
        assertEquals(0, restarts)
    }

    @Test fun theConfirmStepCountsSettingsThatWillBeSkipped() = runBlocking {
        val bytes = zipped(file(settings = mapOf<String, Any>("data_saver" to "x")))
        val vm = com.spotifry.music.ui.settings.BackupViewModel(backups, { null }, { ByteArrayInputStream(bytes) }, {})
        vm.read(android.net.Uri.EMPTY)
        val step = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { vm.step.first { it is com.spotifry.music.ui.settings.BackupStep.Confirm } }
        } as com.spotifry.music.ui.settings.BackupStep.Confirm
        assertEquals(1, step.unusableSettings)
    }

    @Test fun aSuccessfulRestoreRestarts() = runBlocking {
        val restarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        vmFor(backups) { restarted.complete(Unit) }.restore(file(songs = listOf(song("a"))))
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.withTimeout(5_000) { restarted.await() } }
    }

    @Test fun anEntryOverTheLimitIsRefusedThoughItIsValid() {
        val pad = "x".repeat(MAX_JSON_BYTES)
        assertTrue(refused(zipOf("backup.json", """{"format":1,"pad":"$pad"}""")).contains("isn't a Spotifry backup"))
        assertEquals(1, readBackup(ByteArrayInputStream(zipOf("backup.json", """{"format":1,"pad":"x","songs":[{"id":"a"}]}"""))).songs.size)
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
        assertEquals(emptyList<SongNoteEntity>(), b.notes) // a backup from before notes
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
