package com.spotifry.music.ui.playlists

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.testSong
import com.spotifry.music.player.SongPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class NoopPlayer : SongPlayer { override fun playList(songs: List<Song>, startIndex: Int, from: String?) {} }

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistsTest {
    private lateinit var db: AppDatabase
    private lateinit var vm: PlaylistsViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
        vm = PlaylistsViewModel(db.playlists(), db.songs(), NoopPlayer(), coverFiles = com.spotifry.music.data.PlaylistCovers(coverDir))
    }
    private val coverDir = java.io.File.createTempFile("covers", "").let { it.delete(); it.mkdirs(); it }
    private fun picture() = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
    @After fun tearDown() { Dispatchers.resetMain(); db.close() }

    private suspend fun <T> StateFlow<T>.await(p: (T) -> Boolean): T = withContext(Dispatchers.Default) { withTimeout(5_000) { first(p) } }

    @Test fun plateNumbersStayPutWhenAnEarlierPlaylistIsDeleted() = runBlocking<Unit> {
        vm.create("One"); vm.create("Two"); vm.create("Three")
        val all = vm.playlists.await { it.size == 3 }
        vm.delete(all[0].id)
        assertEquals(listOf(all[1].number, all[2].number), vm.playlists.await { it.size == 2 }.map { it.number })
    }

    @Test fun playAllStartsAtTheTopInThePlaylistsOwnOrder() = runBlocking<Unit> {
        var got: Triple<List<String>, Int, String?>? = null
        val player = object : SongPlayer { override fun playList(songs: List<Song>, startIndex: Int, from: String?) { got = Triple(songs.map { it.id }, startIndex, from) } }
        val v = PlaylistsViewModel(db.playlists(), db.songs(), player)
        v.create("Mix")
        val p = v.playlists.await { it.isNotEmpty() }.single()
        v.addSong(p.id, testSong.copy(id = "a")); v.addSong(p.id, testSong.copy(id = "b"))
        v.playAll(p)
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (got?.first?.size != 2) { kotlinx.coroutines.delay(10); v.playAll(p) } } }
        assertEquals(Triple(listOf("a", "b"), 0, "Mix"), got)
    }

    @Test fun coversListFirstFourSongsPerPlaylist() = runBlocking<Unit> {
        vm.create("Mix")
        val id = vm.playlists.await { it.isNotEmpty() }.single().id
        (1..5).forEach { vm.addSong(id, testSong.copy(id = "s$it")) }
        assertEquals(listOf("s1", "s2", "s3", "s4"), vm.covers.await { it[id]?.size == 4 }.getValue(id).map { it.id })
    }

    @Test fun createRenameDeleteRoundTrip() = runBlocking<Unit> {
        vm.create("Road trip")
        assertEquals("Road trip", vm.playlists.await { it.isNotEmpty() }.single().name)
        val id = vm.playlists.value.single().id
        vm.rename(id, "Night drive")
        assertEquals("Night drive", vm.playlists.await { it.firstOrNull()?.name == "Night drive" }.single().name)
        vm.delete(id)
        vm.playlists.await { it.isEmpty() }
    }

    @Test fun addAndRemoveSongKeepsCountsRight() = runBlocking<Unit> {
        vm.create("Mix")
        val id = vm.playlists.await { it.isNotEmpty() }.single().id
        vm.addSong(id, testSong)
        assertEquals(1, vm.playlists.await { it.single().songCount == 1 }.single().songCount)
        vm.removeSong(id, testSong.id)
        vm.playlists.await { it.single().songCount == 0 }
    }

    @Test fun deletingPlaylistKeepsSongRowsThePlayerReads() = runBlocking<Unit> {
        vm.create("Mix")
        val id = vm.playlists.await { it.isNotEmpty() }.single().id
        vm.addSong(id, testSong)
        vm.playlists.await { it.single().songCount == 1 }
        vm.delete(id)
        vm.playlists.await { it.isEmpty() }
        assertEquals(testSong.id, db.songs().get(testSong.id)?.id)
    }

    @Test fun ticksShowThePlaylistsThatHoldTheSong() = runBlocking<Unit> {
        vm.create("Chill"); vm.create("Gym"); vm.create("Road trip")
        val (chill, _, road) = vm.playlists.await { it.size == 3 }.map { it.id }
        vm.addSong(chill, testSong); vm.addSong(road, testSong)
        assertEquals(setOf(chill, road), vm.playlistsWith(testSong.id).first { it.size == 2 })
    }

    @Test fun undoPutsTheSongBackInTheSameSpot() = runBlocking<Unit> {
        vm.create("Mix")
        val id = vm.playlists.await { it.isNotEmpty() }.single().id
        (1..4).forEach { vm.addSong(id, testSong.copy(id = "s$it")) }
        vm.playlists.await { it.single().songCount == 4 }
        val row = vm.takeOut(id, "s2")!!
        vm.addSong(id, testSong.copy(id = "s5"))
        vm.playlists.await { it.single().songCount == 4 }
        vm.putBack(row)
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), vm.songsOf(id).first().map { it.id })
        assertEquals(row, db.playlists().row(id, "s2"))
    }

    @Test fun undoAfterAReorderStillGoesInAtThatPosition() = runBlocking<Unit> {
        vm.create("Mix")
        val id = vm.playlists.await { it.isNotEmpty() }.single().id
        (1..3).forEach { vm.addSong(id, testSong.copy(id = "s$it")) }
        vm.playlists.await { it.single().songCount == 3 }
        val row = vm.takeOut(id, "s1")!!
        db.playlists().setOrder(id, listOf("s3", "s2")) // positions 0 and 1: s1's old spot is taken
        vm.putBack(row)
        assertEquals(listOf("s1", "s3", "s2"), vm.songsOf(id).first().map { it.id })
    }

    @Test fun savingAnAlbumTwiceNamesTheCopyWithANumber() = runBlocking<Unit> {
        val songs = listOf(testSong.copy(id = "s1"), testSong.copy(id = "s2"))
        suspend fun save(): String {
            val got = kotlinx.coroutines.CompletableDeferred<String>()
            vm.createWith("Low Season", songs) { _, name -> got.complete(name) }
            return withContext(Dispatchers.Default) { withTimeout(5_000) { got.await() } }
        }
        assertEquals("Low Season", save())
        vm.playlists.await { it.size == 1 }
        assertEquals("Low Season (2)", save())
        assertEquals(listOf(2, 2), vm.playlists.await { p -> p.size == 2 && p.all { it.songCount == 2 } }.map { it.songCount })
    }

    @Test fun aNewCoverReplacesTheOldAndUndoBringsTheOldBack() = runBlocking<Unit> {
        vm.create("Mix")
        val id = vm.playlists.await { it.isNotEmpty() }.single().id
        val first = vm.setCover(id, picture())
        vm.keepCover(first)
        val name1 = vm.playlists.await { it.single().cover != null }.single().cover!!
        assertEquals(1, coverDir.listFiles()!!.size)
        Thread.sleep(3)
        val second = vm.setCover(id, picture())
        assertEquals(name1, second.old)
        // both files exist until the choice is final, so Undo has something to go back to
        assertEquals(2, coverDir.listFiles()!!.size)
        vm.undoCover(id, second)
        assertEquals(name1, vm.playlists.await { it.single().cover == name1 }.single().cover)
        assertEquals(listOf(name1), coverDir.listFiles()!!.map { it.name })
    }

    @Test fun keepingTheChangeDeletesTheOldPictureAndTheDefaultCoverClearsIt() = runBlocking<Unit> {
        vm.create("Mix")
        val id = vm.playlists.await { it.isNotEmpty() }.single().id
        vm.keepCover(vm.setCover(id, picture()))
        Thread.sleep(3)
        val back = vm.setCover(id, null)
        vm.keepCover(back)
        vm.playlists.await { it.single().cover == null }
        assertEquals(0, coverDir.listFiles()!!.size)
    }

    @Test fun deletingAPlaylistDeletesItsPicture() = runBlocking<Unit> {
        vm.create("Mix")
        val id = vm.playlists.await { it.isNotEmpty() }.single().id
        vm.keepCover(vm.setCover(id, picture()))
        assertEquals(1, coverDir.listFiles()!!.size)
        vm.delete(id)
        vm.playlists.await { it.isEmpty() }
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (coverDir.listFiles()!!.isNotEmpty()) kotlinx.coroutines.delay(10) } }
        assertEquals(0, coverDir.listFiles()!!.size)
    }
}
