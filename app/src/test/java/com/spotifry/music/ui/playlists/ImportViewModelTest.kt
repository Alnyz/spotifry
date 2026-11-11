package com.spotifry.music.ui.playlists

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.SpotifyPlaylist
import com.spotifry.music.data.SpotifyTrack
import com.spotifry.music.data.YouTubePlaylist
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportViewModelTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() { Dispatchers.resetMain(); db.close() }

    /** Finds "Found" at the right length; finds nothing for anything else. */
    private val source = object : MusicSource {
        override suspend fun search(query: String) = if (query.startsWith("Found")) listOf(Song("yt1", "Found", "A", 200, null)) else emptyList()
        override suspend fun resolve(song: Song, low: Boolean): ResolvedStream = error("unused")
        override suspend fun related(song: Song): List<Song> = emptyList()
    }

    private fun vm(tracks: List<SpotifyTrack>) = ImportViewModel({ SpotifyPlaylist("chill", tracks) }, source, db.playlists(), db.songs())
    private suspend fun ImportViewModel.awaitEnd() = withContext(Dispatchers.Default) {
        withTimeout(5_000) { state.first { it is ImportState.Done || it is ImportState.Failed } }
    }

    @Test fun makesPlaylistWithFoundSongsAndListsTheRest() = runBlocking<Unit> {
        val vm = vm(listOf(SpotifyTrack("Found", "A", 201), SpotifyTrack("Missing", "B", 180)))
        vm.start("https://open.spotify.com/playlist/abc123?si=x")
        val done = vm.awaitEnd() as ImportState.Done
        assertEquals(1, done.added)
        assertEquals(listOf("Missing"), done.missing.map { it.title })
        assertEquals(listOf("yt1"), db.playlists().observeSongs(done.playlistId).first().map { it.id })
        assertEquals("chill", db.playlists().observePlaylists().first().single().name)
    }

    @Test fun nothingFoundLeavesNoEmptyPlaylist() = runBlocking<Unit> {
        val vm = vm(listOf(SpotifyTrack("Missing", "B", 180)))
        vm.start("https://open.spotify.com/playlist/abc123")
        assertTrue(vm.awaitEnd() is ImportState.Failed)
        assertTrue(db.playlists().observePlaylists().first().isEmpty())
    }

    @Test fun cancelRemovesThePlaylist() = runBlocking<Unit> {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : MusicSource by source {
            override suspend fun search(query: String): List<Song> { gate.await(); return source.search(query) }
        }
        val vm = ImportViewModel({ SpotifyPlaylist("chill", listOf(SpotifyTrack("Found", "A", 201))) }, slow, db.playlists(), db.songs())
        vm.start("https://open.spotify.com/playlist/abc123")
        withContext(Dispatchers.Default) { withTimeout(5_000) { vm.state.first { it is ImportState.Running } } }
        vm.cancel()
        assertEquals(ImportState.Closed, vm.state.value)
        withContext(Dispatchers.Default) { withTimeout(5_000) { db.playlists().observePlaylists().first { it.isEmpty() } } }
    }

    @Test fun searchesThreeAtOnceAndKeepsSpotifyOrder() = runBlocking<Unit> {
        var running = 0; var most = 0
        val lock = Any()
        val counting = object : MusicSource by source {
            // on IO like the real search; the test Main dispatcher's clock never moves on its own
            override suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
                val n = query.substringBefore(' ').removePrefix("Found").toInt()
                synchronized(lock) { running++; most = maxOf(most, running) }
                kotlinx.coroutines.delay(50L * (8 - n)) // later songs answer first
                synchronized(lock) { running-- }
                listOf(Song("yt$n", "Found$n", "A", 200, null))
            }
        }
        val tracks = (1..7).map { SpotifyTrack("Found$it", "A", 200) }
        val vm = ImportViewModel({ SpotifyPlaylist("chill", tracks) }, counting, db.playlists(), db.songs())
        vm.start("https://open.spotify.com/playlist/abc123")
        val done = vm.awaitEnd() as ImportState.Done
        assertEquals(3, most)
        assertEquals((1..7).map { "yt$it" }, db.playlists().observeSongs(done.playlistId).first().map { it.id })
    }

    @Test fun wrongLinkSaysSo() {
        val vm = vm(emptyList())
        vm.start("https://youtube.com/watch?v=1")
        assertEquals(ImportState.Input("That isn't a Spotify or YouTube playlist link"), vm.state.value)
    }

    private val ytLink = "https://music.youtube.com/playlist?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"
    private fun ytVm(fetch: suspend (String) -> YouTubePlaylist) =
        ImportViewModel({ error("no Spotify here") }, source, db.playlists(), db.songs(), fetch)

    @Test fun youTubeSongsGoStraightInWithoutSearching() = runBlocking<Unit> {
        var asked: String? = null
        val songs = (1..3).map { Song("yt$it", "Song $it", "A", 200, null) }
        val vm = ImportViewModel({ error("no Spotify here") }, object : MusicSource by source {
            override suspend fun search(query: String): List<Song> = error("must not search")
        }, db.playlists(), db.songs()) { asked = it; YouTubePlaylist("road trip", songs, skipped = 2, capped = false) }
        vm.start(ytLink)
        val done = vm.awaitEnd() as ImportState.Done
        assertEquals("https://www.youtube.com/playlist?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf", asked)
        assertEquals(3, done.added)
        assertEquals(5, done.total)
        assertEquals("2 skipped: unavailable or too long.", done.note)
        assertEquals(listOf("yt1", "yt2", "yt3"), db.playlists().observeSongs(done.playlistId).first().map { it.id })
        assertEquals("road trip", db.playlists().observePlaylists().first().single().name)
    }

    @Test fun cutMixSaysSo() = runBlocking<Unit> {
        val vm = ytVm { YouTubePlaylist("Mix", listOf(Song("yt1", "S", "A", 200, null)), 0, capped = true) }
        vm.start("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ")
        assertEquals("Mixes never end, so only the first 500 were taken.", (vm.awaitEnd() as ImportState.Done).note)
    }

    @Test fun privateYouTubeListSaysSo() = runBlocking<Unit> {
        val vm = ytVm { throw ContentNotAvailableException("This playlist is private") }
        vm.start(ytLink)
        assertEquals(ImportState.Failed("That playlist is private or needs sign-in. Only public playlists can be imported."), vm.awaitEnd())
        assertTrue(db.playlists().observePlaylists().first().isEmpty())
    }

    @Test fun youTubeOfflineOrEmptySaysSo() = runBlocking<Unit> {
        val offline = ytVm { throw java.io.IOException("no network") }
        offline.start(ytLink)
        assertEquals(ImportState.Failed("Couldn't open that playlist. Check it's public and you're online."), offline.awaitEnd())
        val broken = ytVm { throw org.schabi.newpipe.extractor.exceptions.ParsingException("changed page") }
        broken.start(ytLink)
        assertEquals(ImportState.Failed("Couldn't open that playlist. Check it's public and you're online."), broken.awaitEnd())
        val empty = ytVm { YouTubePlaylist("nothing", emptyList(), 4, false) }
        empty.start(ytLink)
        assertEquals(ImportState.Failed("That playlist has no songs."), empty.awaitEnd())
        assertTrue(db.playlists().observePlaylists().first().isEmpty())
    }
}
