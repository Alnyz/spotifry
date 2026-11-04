package com.spotifry.music.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.FakeMusicSource
import com.spotifry.music.data.StreamResolver
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.testSong
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SongDataSourceTest {
    private lateinit var db: AppDatabase
    private val source = FakeMusicSource()
    private lateinit var resolver: SongDataSourceResolver

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        resolver = SongDataSourceResolver(StreamResolver(source) { 1_000L }, db.songs())
    }
    @After fun tearDown() = db.close()

    private fun spec() = DataSpec.Builder().setUri(Uri.parse("music://song")).setKey(testSong.id).build()

    @Test fun savedSongPlaysFromFileWithoutNetwork() = runBlocking {
        db.songs().upsert(testSong.toEntity())
        db.songs().markSaved(testSong.id, "/data/songs/abc.m4a", null, 5)
        source.failResolve = true
        val out = resolver.resolveDataSpec(spec())
        assertEquals("file", out.uri.scheme)
        assertEquals("/data/songs/abc.m4a", out.uri.path)
        assertEquals(0, source.resolveCalls)
    }

    @Test fun unsavedSongUsesResolvedStreamUrl() = runBlocking {
        db.songs().upsert(testSong.toEntity())
        assertEquals("https://x/1", resolver.resolveDataSpec(spec()).uri.toString())
    }

    @Test fun savedFileResolverSendsOnlySavedSongsToTheirFile() = runBlocking {
        db.songs().upsert(testSong.toEntity())
        val saved = SavedFileResolver(db.songs())
        assertEquals("music", saved.resolveDataSpec(spec()).uri.scheme)
        db.songs().markSaved(testSong.id, "/data/songs/abc.m4a", null, 5)
        assertEquals("/data/songs/abc.m4a", saved.resolveDataSpec(spec()).uri.path)
    }

    /** Same chain as PlaybackService: the second play of a song comes from the cache, under its id. */
    @Test fun secondPlayComesFromTheSongCache() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dir = java.io.File(ctx.cacheDir, "song-cache-test").apply { deleteRecursively() }
        val cache = androidx.media3.datasource.cache.SimpleCache(dir, androidx.media3.datasource.cache.NoOpCacheEvictor(), androidx.media3.database.StandaloneDatabaseProvider(ctx))
        val audio = ByteArray(10_000) { it.toByte() }
        val keys = mutableListOf<String?>()
        val upstream = androidx.media3.datasource.ResolvingDataSource.Factory(
            { androidx.media3.datasource.ByteArrayDataSource(audio) },
            { s -> keys += s.key; s }, // runs only when the song is fetched, not when it comes from the cache
        )
        val cached = androidx.media3.datasource.cache.CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
        repeat(2) {
            val ds = cached.createDataSource()
            ds.open(spec())
            val out = androidx.media3.datasource.DataSourceUtil.readToEnd(ds)
            ds.close()
            assertEquals(audio.toList(), out.toList())
        }
        assertEquals(listOf<String?>(testSong.id), keys)
        cache.release()
    }

    @Test fun failedResolveBecomesIOException() {
        source.failResolve = true
        assertThrows(IOException::class.java) { resolver.resolveDataSpec(spec()) }
    }
}
