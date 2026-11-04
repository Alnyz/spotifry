package com.spotifry.music.data

import com.spotifry.music.data.source.NewPipeSource
import com.spotifry.music.data.source.OkHttpDownloader
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization

/** Hits the real YouTube. Run with: ./gradlew testDebugUnitTest -Plive=1 --tests '*NewPipeSourceLiveTest' */
class NewPipeSourceLiveTest {
    @Test fun searchResolveRelatedWork() = runBlocking {
        assumeTrue(System.getProperty("live").orEmpty().isNotEmpty())
        NewPipe.init(OkHttpDownloader(OkHttpClient()), Localization("en", "US"))
        val source = NewPipeSource()
        val songs = source.search("coldplay yellow")
        assertTrue(songs.isNotEmpty())
        val stream = source.resolve(songs.first())
        assertTrue(stream.url.startsWith("https://"))
        assertTrue(source.related(songs.first()).isNotEmpty())
    }
}
