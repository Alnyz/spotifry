package com.spotifry.desktop

import com.spotifry.music.data.StreamResolver
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Plays real (silent, 1 second) wav files through a real mpv with its sound output turned off. Skipped when mpv isn't installed. */
class MpvPlayerTest {
    private val dir = java.nio.file.Files.createTempDirectory("spotifry-test").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var player: MpvPlayer? = null

    private val songs = (0..2).map { Song("t$it", "T$it", "A$it", 1, null) }

    private val source = object : MusicSource {
        override suspend fun search(query: String) = emptyList<Song>()
        override suspend fun related(song: Song) = emptyList<Song>()
        override suspend fun resolve(song: Song, low: Boolean): ResolvedStream {
            if (song.id == "bad") throw java.io.FileNotFoundException("no audio")
            if (song.id == "slow") kotlinx.coroutines.delay(1_500) // a link that takes a while, like YouTube's
            return ResolvedStream(File(dir, "${song.id}.wav").path, "audio/wav", 0, Long.MAX_VALUE)
        }
    }

    @Before fun setUp() {
        assumeTrue("mpv not installed", runCatching { ProcessBuilder("mpv", "--version").start().waitFor() == 0 }.getOrDefault(false))
        (songs + Song("bad", "Bad", "A", 1, null)).forEach { File(dir, "${it.id}.wav").writeBytes(silence(seconds = 1)) }
        player = MpvPlayer(StreamResolver(source, scope), scope, File(dir, "last.json"), listOf("--ao=null"))
    }

    @After fun tearDown() { player?.close(); dir.deleteRecursively() }

    private fun silence(seconds: Int): ByteArray {
        val rate = 8000; val data = ByteArray(rate * seconds * 2)
        return ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(rate); putInt(rate * 2); putShort(2); putShort(16); put("data".toByteArray()); putInt(data.size); put(data)
        }.array()
    }

    private fun <T> wait(what: String, ms: Long = 15_000, block: suspend () -> T): T = runBlocking {
        try { withTimeout(ms) { block() } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what: ${player!!.state.value}") }
    }

    @Test fun playsTheWholeQueueAndEnds() {
        val p = player!!
        val seen = mutableListOf<Int>()
        val watcher = scope.let { kotlinx.coroutines.GlobalScope }.let { _ -> Thread { runBlocking { p.state.collect { if (seen.lastOrNull() != it.currentIndex) seen += it.currentIndex } } }.apply { isDaemon = true; start() } }
        p.playList(songs, 0, "Test")
        wait("the queue to end") { p.state.first { it.ended } }
        assertEquals(listOf(0, 1, 2), seen)
        assertFalse(p.state.value.isPlaying)
        assertEquals("Test", p.state.value.from)
        watcher.interrupt()
    }

    @Test fun pauseHoldsThePlaceAndPlayGoesOn() {
        val p = player!!
        p.playList(songs, 0)
        wait("sound to start") { p.state.first { it.isPlaying } }
        p.pause()
        assertFalse(p.state.value.wantsPlay)
        p.toggle()
        assertTrue(p.state.value.wantsPlay)
        wait("playing again") { p.state.first { it.isPlaying } }
    }

    @Test fun nextAndPreviousMoveThroughTheList() {
        val p = player!!
        p.playList(songs, 0)
        wait("the first song") { p.state.first { it.isPlaying } }
        p.next()
        wait("the second song") { p.state.first { it.currentIndex == 1 && it.isPlaying } }
        p.next()
        wait("the third song") { p.state.first { it.currentIndex == 2 && it.isPlaying } }
        p.next() // at the end with repeat off: stays
        assertEquals(2, p.state.value.currentIndex)
        Thread.sleep(500) // position under 3 s: previous goes to the earlier song
        p.previous()
        wait("back to the second song") { p.state.first { it.currentIndex == 1 } }
    }

    @Test fun repeatAllStartsOverAndRepeatOneStays() {
        val p = player!!
        p.cycleRepeat(); assertEquals(RepeatMode.ALL, p.state.value.repeatMode)
        p.playList(songs.take(2), 1)
        wait("wrap around to the first song") { p.state.first { it.currentIndex == 0 } }
        p.cycleRepeat(); assertEquals(RepeatMode.ONE, p.state.value.repeatMode)
        Thread.sleep(2500)
        assertEquals(0, p.state.value.currentIndex)
        assertTrue(p.state.value.wantsPlay)
    }

    @Test fun shuffleKeepsThePlayingSongFirstAndPlaysEveryOne() {
        val p = player!!
        p.playList(songs, 1)
        p.cycleShuffle()
        val order = p.state.value.order!!
        assertEquals(1, order.first()); assertEquals(setOf(0, 1, 2), order.toSet())
        p.cycleShuffle(); assertTrue(p.state.value.smart) // the second press: Smart shuffle
        p.cycleShuffle(); assertEquals(null, p.state.value.order)
    }

    @Test fun aSongWithNoAudioIsSkipped() {
        val p = player!!
        val list = listOf(songs[0], Song("bad", "Bad", "A", 1, null), songs[2])
        p.playList(list.take(1) + list.drop(1), 1)
        wait("to move past the broken song") { p.state.first { it.currentIndex == 2 } }
    }

    @Test fun aTrimPlaysOnlyItsPartAndCanChangeWhilePlaying() {
        File(dir, "long.wav").writeBytes(silence(seconds = 4))
        val trims = com.spotifry.music.player.Trims(FilePrefs(File(dir, "trims.json")))
        trims["long"] = com.spotifry.music.player.Trim(1_000, 2_500) // as a phone backup brings it
        player!!.close()
        val p = MpvPlayer(StreamResolver(source, scope), scope, null, listOf("--ao=null"), trims = trims).also { player = it }
        val long = Song("long", "Long", "A", 4, null)
        p.playList(listOf(long, songs[0]), 0)
        wait("the trimmed song to play") { p.state.first { it.isPlaying && it.wholeMs > 3_500 } }
        assertEquals(1_500L, p.state.value.durationMs)
        assertTrue(p.position.value in 0..1_500)
        val started = System.currentTimeMillis()
        wait("the next song") { p.state.first { it.currentIndex == 1 } }
        assertTrue("ended at its trim, not at 4 s", System.currentTimeMillis() - started < 2_500)

        // the menu's "Start this song here" keeps the song going; "End this song here" moves on
        trims["long"] = null
        p.playList(listOf(long, songs[0]), 0)
        wait("the whole song") { p.state.first { it.isPlaying && it.durationMs > 3_500 } }
        p.setTrim("long", com.spotifry.music.player.Trim(500))
        assertEquals(500L, p.state.value.trim?.startMs)
        assertEquals(p.state.value.wholeMs - 500, p.state.value.durationMs)
        assertEquals(0, p.state.value.currentIndex)
        p.setTrim("long", com.spotifry.music.player.Trim(500, 1_000), skip = true)
        wait("the next song after End here") { p.state.first { it.currentIndex == 1 } }
        assertEquals(com.spotifry.music.player.Trim(500, 1_000), trims["long"])
    }

    @Test fun aSmartTrimPlaysWhileOnAndPlayTheWholeSongKeepsItAway() {
        File(dir, "long.wav").writeBytes(silence(seconds = 4))
        val prefs = FilePrefs(File(dir, "smart.json"))
        com.spotifry.music.player.Trims(prefs)["long"] = com.spotifry.music.player.Trim(1_000) // as if SponsorBlock had answered
        val smart = com.spotifry.music.player.SmartTrims(prefs, okhttp3.OkHttpClient())
        var on = false
        player!!.close()
        val p = MpvPlayer(StreamResolver(source, scope), scope, null, listOf("--ao=null"), smartTrims = smart, smartTrimOn = { on }).also { player = it }
        val long = Song("long", "Long", "A", 4, null)
        p.playList(listOf(long), 0)
        wait("the whole song with smart trim off") { p.state.first { it.isPlaying && it.wholeMs > 3_500 } }
        assertEquals(null, p.state.value.trim)
        on = true
        p.playList(listOf(long), 0)
        wait("the smart trim") { p.state.first { it.isPlaying && it.trim != null } }
        assertEquals(1_000L, p.state.value.trim!!.startMs)
        p.setTrim("long", null)
        assertEquals(null, p.state.value.trim)
        assertEquals(null, smart["long"])
    }

    @Test fun soundFiltersGoOnAndOffWhilePlaying() {
        val p = player!!
        p.playList(songs, 0)
        wait("sound to start") { p.state.first { it.isPlaying } }
        p.setFilter("loud", Sound.LOUDNESS); p.setFilter("quiet", Sound.SKIP_QUIET)
        p.setFilter("loud", null)
        wait("the queue to end with filters on") { p.state.first { it.ended } }
    }

    @Test fun aJumpWhileTheSongIsStillStartingIsKept() {
        File(dir, "slow.wav").writeBytes(silence(seconds = 6))
        val p = player!!
        p.playList(listOf(Song("slow", "Slow", "A", 6, null)), 0)
        Thread.sleep(300) // still asking for the link
        p.seekTo(4_000)
        wait("the song to play") { p.state.first { it.isPlaying } }
        Thread.sleep(500)
        assertTrue("plays from the jump, not from the start: ${p.position.value}", p.position.value >= 3_900)
    }

    @Test fun smartShuffleAddsOneSuggestionAfterEveryThreeAndShuffleOffTakesThemOut() {
        val mine = (0 until 9).map { Song("m$it", "M$it", "Artist $it", 60, null) }
        val similar = (0 until 10).map { Song("s$it", "S$it", "Other", 60, null) }
        mine.forEach { File(dir, "${it.id}.wav").writeBytes(silence(seconds = 1)) }
        player!!.close()
        val p = MpvPlayer(StreamResolver(source, scope), scope, null, listOf("--ao=null"), related = { similar }, notSuggested = { setOf("s0") }).also { player = it }
        p.playList(mine, 0); p.pause()
        p.cycleShuffle(); assertTrue(p.state.value.shuffle); assertFalse(p.state.value.smart)
        p.cycleShuffle(); assertTrue(p.state.value.smart)
        wait("the suggestions") { p.state.first { s -> s.queue.any { it.suggested } } }
        val s = p.state.value
        val next = s.order!!.drop(1).map { s.queue[it] }
        // every fourth song is a suggestion: three of yours, then one
        next.forEachIndexed { i, song -> assertEquals("place $i", i % 4 == 3, song.suggested) }
        assertFalse("never a song marked Not this song", s.queue.any { it.id == "s0" })
        p.cycleShuffle()
        assertFalse(p.state.value.shuffle)
        assertEquals(mine, p.state.value.queue)
    }

    @Test fun theQueueComesBackAfterARestart() {
        val p = player!!
        p.playList(songs, 1, "Saved")
        wait("sound to start") { p.state.first { it.isPlaying } }
        p.close()
        val again = MpvPlayer(StreamResolver(source, scope), scope, File(dir, "last.json"), listOf("--ao=null"))
        try {
            val s = again.state.value
            assertEquals(1, s.currentIndex); assertEquals(3, s.queue.size); assertEquals("Saved", s.from)
            assertFalse(s.wantsPlay) // comes back paused
        } finally { again.close() }
    }

    @Test fun thePlayRecordsHistoryAndTheListen() {
        val db = androidx.room.Room.inMemoryDatabaseBuilder<com.spotifry.music.data.db.AppDatabase>(factory = { com.spotifry.music.data.db.AppDatabase_Impl() })
            .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
        val recorder = PlayRecorder(com.spotifry.music.data.HistoryRecorder(db.history(), db.songs()), com.spotifry.music.data.ListenLog(db.listens(), scope)) { db.plays().count(it, 1L) }
        player?.close()
        val p = MpvPlayer(StreamResolver(source, scope), scope, null, listOf("--ao=null"), recorder = recorder)
        try {
            File(dir, "long.wav").writeBytes(silence(seconds = 4))
            p.playList(listOf(Song("long", "Long", "A", 4, null)), 0)
            wait("the song to end") { p.state.first { it.ended } }
            wait("the song in history") { db.history().observeRecent().first { list -> list.isNotEmpty() } }
            assertEquals("long", runBlocking { db.history().observeRecent().first().first().id })
            // the listen is written once sound has run for a second, and updated as it goes
            wait("a listen with seconds heard") { db.listens().observeAll().first { rows -> rows.isNotEmpty() } }
        } finally { p.close(); db.close() }
    }
}
