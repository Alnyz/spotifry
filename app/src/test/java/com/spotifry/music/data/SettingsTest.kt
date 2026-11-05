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

    @Test fun theOldEqualizerCarriesOver() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("eq-old", 0)
        prefs.edit().putBoolean("eq_on", true).putString("eq_preset", "Rock").putString("eq_levels", "500,300,-100,300,500").apply()
        val s = Settings(prefs)
        assertEquals(true, s.eqOn.value)
        assertEquals("Rock", s.eqPreset.value)
        assertEquals(com.spotifry.music.player.eqFromOldBands(listOf(500, 300, -100, 300, 500)), s.eqLevels.value)
    }

    @Test fun aPresetOnlyThatPhoneHadBecomesTheUsersOwnMix() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("eq-maker", 0)
        prefs.edit().putString("eq_preset", "Maker Bass").putString("eq_levels", "900,0,0,0,0").apply()
        val s = Settings(prefs)
        assertEquals(null, s.eqPreset.value)
        assertEquals(900, s.eqLevels.value[0])
    }

    @Test fun nothingSavedIsFlat() {
        val s = Settings(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("eq-none", 0))
        assertEquals(List(10) { 0 }, s.eqLevels.value)
        assertEquals(false, s.eqOn.value)
    }

    @Test fun tenBandsSurviveARestartAndBeatTheOldOnes() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("eq-new", 0)
        prefs.edit().putString("eq_levels", "500,300,-100,300,500").apply()
        val mine = List(10) { it * 50 }
        Settings(prefs).setEq("Mine", mine)
        val again = Settings(prefs)
        assertEquals(mine, again.eqLevels.value)
        assertEquals("Mine", again.eqPreset.value)
    }

    @Test fun aDragIsHeardAtOnceAndSavedWhenTheFingerLifts() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("eq-drag", 0)
        val s = Settings(prefs)
        val mine = List(10) { 100 }
        s.setEq(null, mine, save = false)
        assertEquals(mine, s.eqLevels.value)
        assertEquals(List(10) { 0 }, Settings(prefs).eqLevels.value)
        s.saveEq()
        assertEquals(mine, Settings(prefs).eqLevels.value)
    }

    @Test(expected = IllegalArgumentException::class) fun theWrongNumberOfBandsIsRefused() {
        Settings(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("eq-size", 0)).setEq(null, listOf(1, 2, 3))
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
