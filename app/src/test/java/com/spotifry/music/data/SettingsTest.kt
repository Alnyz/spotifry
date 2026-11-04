package com.spotifry.music.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsTest {
    @Test fun choicesSurviveARestart() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("t", 0)
        Settings(prefs).apply { setDataSaver(true); setSaveLow(true); setTheme(ThemeMode.DARK) }
        val again = Settings(prefs)
        assertEquals(true, again.dataSaver.value)
        assertEquals(true, again.saveLow.value)
        assertEquals(ThemeMode.DARK, again.theme.value)
    }

    @Test fun lowAndHighQualityAreKeptApart() = runBlocking {
        val asked = mutableListOf<Boolean>()
        val source = object : MusicSource {
            override suspend fun search(query: String) = emptyList<Song>()
            override suspend fun related(song: Song) = emptyList<Song>()
            override suspend fun resolve(song: Song, low: Boolean): ResolvedStream { asked += low; return ResolvedStream(if (low) "low" else "high", "audio/webm", 0, Long.MAX_VALUE) }
        }
        val r = StreamResolver(source)
        val song = Song("a", "A", "B", 1, null)
        assertEquals("high", r.get(song).url)
        assertEquals("low", r.get(song, low = true).url)
        assertEquals("high", r.get(song).url)
        assertEquals(listOf(false, true), asked)
    }
}
