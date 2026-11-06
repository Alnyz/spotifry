package com.spotifry.music.data

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.source.ChoosingSource
import com.spotifry.music.data.source.JioSaavnSource
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.SaavnResult
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.source.decryptMediaUrl
import com.spotifry.music.data.source.isSameSong
import com.spotifry.music.data.source.parseSaavnResults
import com.spotifry.music.data.source.url320
import com.spotifry.music.player.SongDataSourceResolver
import com.spotifry.music.player.SourceChangedException
import com.spotifry.music.player.songFromUri
import com.spotifry.music.player.toMediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

private val youtube = Song("yt", "Tum Hi Ho", "Arijit Singh", 262, null)
private fun result(title: String = "Tum Hi Ho", artists: List<String> = listOf("Mithoon", "Arijit Singh"), album: String = "Aashiqui 2", sec: Long = 262, has320: Boolean = true) =
    SaavnResult(title, artists, album, sec, has320, "")

class JioSaavnMatcherTest {
    private fun same(song: Song, r: SaavnResult) = isSameSong(song, r)

    @Test fun theSameSongMatches() = assertTrue(same(youtube, result()))
    @Test fun extrasOnEitherSideDoNotMatter() {
        for (t in listOf("Tum Hi Ho (Official Video)", "Tum Hi Ho - Remastered 2011", "Tum Hi Ho [2013 Remaster]", "Tum Hi Ho (feat. Someone)", "Tum Hi Ho ft. Someone", "TUM HI HO!", "Tum Hi Ho | Official Lyric Video"))
            assertTrue(t, same(youtube.copy(title = t), result()))
        assertTrue(same(youtube, result(title = "Tum Hi Ho (From \"Aashiqui 2\")")))
    }
    @Test fun youtubeChannelNamesCount() {
        assertTrue(same(youtube.copy(artist = "Arijit Singh - Topic"), result()))
        assertTrue(same(youtube.copy(artist = "Beyoncé"), result(artists = listOf("Beyonce"))))
        assertTrue(same(youtube.copy(artist = "Arijit Singh, Mithoon"), result(artists = listOf("Arijit Singh"))))
    }
    @Test fun otherVersionsDoNotMatch() {
        for (t in listOf("Tum Hi Ho (Live)", "Tum Hi Ho - Live at Wembley", "Tum Hi Ho (Remix)", "Tum Hi Ho (Cover)", "Tum Hi Ho (Karaoke Version)", "Tum Hi Ho (Instrumental)", "Tum Hi Ho (Acoustic)", "Tum Hi Ho (Nightcore)", "Tum Hi Ho (Slowed + Reverb)", "Tum Hi Ho Again"))
            assertFalse(t, same(youtube, result(title = t)))
        assertFalse(same(youtube.copy(title = "Tum Hi Ho (Official Live Video)"), result()))
    }
    @Test fun aPlainTitleOnACoverAlbumOrByACoverActDoesNotMatch() {
        assertFalse(same(youtube, result(album = "Karaoke Hits Vol. 3")))
        assertFalse(same(youtube, result(album = "Dolce Remixes")))
        assertFalse(same(youtube, result(album = "Live at Wembley")))
        assertFalse(same(youtube, result(artists = listOf("Arijit Singh", "Tribute Stars"))))
    }
    @Test fun aLiveSongMatchesItsOwnLiveVersion() =
        assertTrue(same(youtube.copy(title = "Tum Hi Ho (Live)"), result(title = "Tum Hi Ho (Live)", album = "Live at Wembley")))
    @Test fun anotherArtistDoesNotMatch() {
        assertFalse(same(youtube, result(artists = listOf("Nxsser"))))
        assertFalse(same(youtube, result(artists = emptyList())))
        // part of a name is not the name
        assertFalse(same(youtube.copy(artist = "Arijit"), result()))
    }
    @Test fun threeSecondsApartIsTheLimit() {
        assertTrue(same(youtube, result(sec = 265)))
        assertTrue(same(youtube, result(sec = 259)))
        assertFalse(same(youtube, result(sec = 266)))
        assertFalse(same(youtube, result(sec = 267)))
        assertFalse(same(youtube, result(sec = 258)))
    }
    @Test fun nothingKnownOrNo320DoesNotMatch() {
        assertFalse(same(youtube, result(has320 = false)))
        assertFalse(same(youtube.copy(durationSec = 0), result()))
        assertFalse(same(youtube.copy(title = ""), result(title = "")))
        // what the search really answered for this one: only other people's songs of the same name
        val song = Song("yt", "Shape of You", "Ed Sheeran", 234, null)
        assertFalse(same(song, result(title = "Shape Of You", artists = listOf("LuxeBeats"), album = "Shape Of You", sec = 192)))
    }

    @Test fun onlyAProperLinkBecomes320() {
        assertEquals("https://aac.saavncdn.com/1/abc_320.mp4", url320("https://aac.saavncdn.com/1/abc_96.mp4"))
        assertNull(url320("http://aac.saavncdn.com/1/abc_96.mp4"))
        assertNull(url320("https://aac.saavncdn.com/1/abc_160.mp4"))
        assertNull(url320(""))
    }

    @Test fun aRealLinkDecrypts() {
        val url = decryptMediaUrl("ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDyRofh8YQPTqjR2tIxTYXqGEQHgqnNfsetouo4rsD0s4RZKObWAyHrEhw7tS9a8Gtq")
        assertTrue(url, url.startsWith("https://aac.saavncdn.com/") && url.endsWith("_96.mp4"))
    }

    @Test fun anEncryptedLinkComesBackTheSame() {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec("38346591".toByteArray(), "DES")) }
        val secret = Base64.getEncoder().encodeToString(cipher.doFinal("https://aac.saavncdn.com/a_96.mp4".toByteArray()))
        assertEquals("https://aac.saavncdn.com/a_96.mp4", decryptMediaUrl(secret))
    }
}

class ChoosingSourceTest {
    private val stream = ResolvedStream("https://aac.saavncdn.com/a_320.mp4", "audio/mp4", 320, Long.MAX_VALUE)
    private val song = Song("a", "A", "B", 100, null)
    private val yt = FakeMusicSource()

    @Test fun aMatchPlaysFromSaavn() = runBlocking { assertEquals(stream, ChoosingSource(yt, { stream }, { true }).resolve(song)); assertEquals(0, yt.resolveCalls) }
    @Test fun noMatchGoesToYoutube() = runBlocking { assertEquals("https://x/1", ChoosingSource(yt, { null }, { true }).resolve(song).url) }
    @Test fun anErrorGoesToYoutube() = runBlocking { assertEquals("https://x/1", ChoosingSource(yt, { error("blocked") }, { true }).resolve(song).url) }
    @Test fun aSlowAnswerGoesToYoutubeInAboutTwoSeconds() = runBlocking {
        val started = System.currentTimeMillis()
        assertEquals("https://x/1", ChoosingSource(yt, { awaitCancellation() }, { true }).resolve(song).url)
        assertTrue(System.currentTimeMillis() - started in 1_900..4_000)
    }
    @Test fun switchOffLowAndPhoneFilesNeverAsk() = runBlocking {
        val asked = mutableListOf<String>()
        val saavn: suspend (Song) -> ResolvedStream? = { asked += it.id; stream }
        assertEquals("https://x/1", ChoosingSource(yt, saavn, { false }).resolve(song).url)
        ChoosingSource(yt, saavn, { true }).resolve(song, low = true)
        ChoosingSource(yt, saavn, { true }).resolve(song.copy(id = "local:5"))
        assertEquals(emptyList<String>(), asked)
    }
    @Test fun everythingElseStaysWithYoutube() = runBlocking {
        val found = listOf(song)
        val own = object : MusicSource by yt { override suspend fun search(query: String) = found }
        assertEquals(found, ChoosingSource(own, { stream }, { true }).search("x"))
    }
    @Test fun flippingTheSwitchTakesEffectAfterClearing() = runBlocking {
        var on = false
        val r = StreamResolver(ChoosingSource(yt, { stream }, { on })) { 1_000L }
        assertEquals("https://x/1", r.get(song).url)
        on = true
        assertEquals("https://x/1", r.get(song).url)
        r.clear()
        assertEquals(stream.url, r.get(song).url)
    }
    @Test fun aLookupRunningDuringAClearIsNotKept() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val r = StreamResolver(object : MusicSource by yt {
            override suspend fun resolve(song: Song, low: Boolean): ResolvedStream { calls++; gate.await(); return stream }
        })
        val waiting = async { r.get(song) }
        while (calls == 0) delay(5)
        r.clear()
        gate.complete(Unit)
        waiting.await()
        r.get(song)
        assertEquals(2, calls)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JioSaavnSourceTest {
    private val answer = """{"results":[
      {"title":"Tum Hi Ho (From &quot;Aashiqui 2&quot;)","more_info":{"music":"Mithoon","album":"Love &amp; Hits","duration":"262","320kbps":"true",
       "encrypted_media_url":"ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDyRofh8YQPTqjR2tIxTYXqGEQHgqnNfsetouo4rsD0s4RZKObWAyHrEhw7tS9a8Gtq",
       "artistMap":{"primary_artists":[{"name":"Mithoon"},{"name":"Arijit Singh"}],"featured_artists":[{"name":"Sai Smriti"}],"artists":[{"name":"Aditya Roy Kapur"}]}}}]}"""

    @Test fun readsTheResultWithPerformersOnly() {
        val r = parseSaavnResults(answer).single()
        assertEquals("Tum Hi Ho (From \"Aashiqui 2\")", r.title)
        assertEquals("Love & Hits", r.album)
        assertEquals(listOf("Mithoon", "Arijit Singh", "Sai Smriti"), r.artists)
        assertEquals(262L, r.durationSec)
        assertTrue(r.has320)
    }
    @Test fun oddAnswersAreEmptyOrThrowNotCrash() {
        assertEquals(emptyList<SaavnResult>(), parseSaavnResults("{}"))
        assertEquals(1, parseSaavnResults("""{"results":[{"title":"x"},{"title":"y","more_info":{}}]}""").size)
        assertFalse(parseSaavnResults("""{"results":[{"title":"y","more_info":{}}]}""").single().has320)
    }

    @Test fun findsTheStreamAndSendsABrowserName() = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody(answer)); start() }
        val found = JioSaavnSource(OkHttpClient(), server.url("/").toString()) { 1_000L }.find(youtube)!!
        assertTrue(found.url, found.url.endsWith("_320.mp4"))
        assertEquals("audio/mp4", found.mimeType)
        assertEquals(320, found.bitrateKbps)
        assertEquals(1_000L + 3_600_000L, found.expiresAtMs)
        val asked = server.takeRequest()
        assertEquals("search.getResults", asked.requestUrl!!.queryParameter("__call"))
        assertEquals("tum hi ho Arijit Singh", asked.requestUrl!!.queryParameter("q"))
        assertTrue(asked.getHeader("User-Agent")!!.startsWith("Mozilla"))
        server.shutdown()
    }
    @Test fun aRefusalIsAnError() = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(403)); start() }
        var failed = false
        try { JioSaavnSource(OkHttpClient(), server.url("/").toString()).find(youtube) } catch (_: java.io.IOException) { failed = true }
        assertTrue(failed)
        server.shutdown()
    }

    @Test fun theSongTravelsInTheItemForAFirstPlay() {
        val uri = youtube.copy(title = "Tum & Hi? Ho/#1").toMediaItem(null).localConfiguration!!.uri
        val back = songFromUri("yt", uri)
        assertEquals("Tum & Hi? Ho/#1", back.title)
        assertEquals("Arijit Singh", back.artist)
        assertEquals(262L, back.durationSec)
    }

    /** Same chain as PlaybackService: bytes in the cache from one source never get joined to the other. */
    @Test fun aSongHalfCachedFromOneSourceStartsOverFromTheOther() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dir = java.io.File(ctx.cacheDir, "one-source-test").apply { deleteRecursively() }
        val cache = SimpleCache(dir, NoOpCacheEvictor(), StandaloneDatabaseProvider(ctx))
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        var url = "https://rr1.googlevideo.com/a"
        val src = object : MusicSource by FakeMusicSource() {
            override suspend fun resolve(song: Song, low: Boolean) = ResolvedStream(url, "audio/mp4", 320, Long.MAX_VALUE)
        }
        val streams = StreamResolver(src)
        val audio = mapOf("https://rr1.googlevideo.com/a" to ByteArray(1_000) { 1 }, "https://aac.saavncdn.com/a_320.mp4" to ByteArray(1_000) { 2 })
        val upstream = ResolvingDataSource.Factory({ object : androidx.media3.datasource.BaseDataSource(false) {
            private lateinit var inner: ByteArrayDataSource
            override fun open(spec: DataSpec): Long { inner = ByteArrayDataSource(audio.getValue(spec.uri.toString())); return inner.open(spec) }
            override fun read(b: ByteArray, o: Int, n: Int) = inner.read(b, o, n)
            override fun getUri() = inner.uri
            override fun close() = inner.close()
        } }, SongDataSourceResolver(streams, db.songs(), cache = cache))
        val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
        fun read(from: Long, n: Long): ByteArray = DataSourceInputStream(source.createDataSource(), DataSpec.Builder().setUri(Uri.parse("music://song/a")).setKey("a").setPosition(from).setLength(n).build()).use { it.readBytes() }

        assertEquals(1.toByte(), read(0, 300).last()) // half played from YouTube
        url = "https://aac.saavncdn.com/a_320.mp4"; streams.clear()
        assertEquals(1.toByte(), read(0, 300).last()) // what's cached needs no link
        assertThrows(SourceChangedException::class.java) { read(0, 1_000) } // the rest would have been the other file
        assertEquals(0L, cache.getCachedBytes("a", 0, 1_000))
        assertTrue(read(0, 1_000).all { it == 2.toByte() }) // the retry is one file from the start
        assertTrue(read(500, 500).all { it == 2.toByte() })
        url = "https://rr1.googlevideo.com/a"; streams.clear()
        assertTrue(read(0, 1_000).all { it == 2.toByte() }) // whole in the cache: it stays until it's evicted
        cache.release(); db.close()
    }
}
