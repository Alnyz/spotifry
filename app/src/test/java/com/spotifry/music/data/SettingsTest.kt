package com.spotifry.music.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun matchingTheBeatIsOffUntilChosenAndSurvivesARestart() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("beat", 0)
        assertFalse(Settings(prefs).beatMatch.value)
        Settings(prefs).setBeatMatch(true)
        assertEquals(true, Settings(prefs).beatMatch.value)
    }

    @Test fun keepScreenOnIsOffUntilChosenAndSurvivesARestart() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("keep-on", 0)
        assertFalse(Settings(prefs).keepScreenOn.value)
        Settings(prefs).setKeepScreenOn(true)
        assertTrue(Settings(prefs).keepScreenOn.value)
    }

    @Test fun theOwnCurveComesBackAfterAHeadphoneCorrection() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("eq-before", 0)
        val own = listOf(100, 0, 0, 0, 0, 0, 0, 0, 0, -50)
        val s = Settings(prefs)
        s.setEq(null, own)
        s.saveEqBefore()
        s.setEq("AutoEq: Some Headphone", List(10) { 300 })
        assertEquals(true, Settings(prefs).eqBeforeSaved.value)
        s.restoreEqBefore()
        assertEquals(own, s.eqLevels.value)
        assertEquals(null, s.eqPreset.value)
        assertEquals(false, s.eqBeforeSaved.value)
        // nothing kept: back to flat, not a crash
        s.setEq("AutoEq: Some Headphone", List(10) { 300 })
        s.restoreEqBefore()
        assertEquals(List(10) { 0 }, s.eqLevels.value)
        assertEquals("Flat", s.eqPreset.value)
    }

    @Test fun aCorrectionThatArrivesAfterAChangeIsDropped() {
        val s = Settings(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("eq-stale", 0))
        val own = listOf(100, 0, 0, 0, 0, 0, 0, 0, 0, -50)
        val fixed = List(10) { 200 }
        s.setEq(null, own); s.setEqOn(true)
        // a first correction goes on and keeps the own curve
        assertTrue(s.applyEqCorrection("AutoEq: A", fixed, null, own, true))
        // the user is back to their own curve while B downloads: B must not land, and nothing is lost
        val seen = s.eqPreset.value; val seenLevels = s.eqLevels.value
        s.restoreEqBefore()
        assertFalse(s.applyEqCorrection("AutoEq: B", fixed, seen, seenLevels, true))
        assertEquals(own, s.eqLevels.value)
        assertEquals(null, s.eqPreset.value)
        // a preset, a drag or the switch during the download count the same
        assertFalse(s.applyEqCorrection("AutoEq: B", fixed, null, own, false))
        assertFalse(s.applyEqCorrection("AutoEq: B", fixed, "Rock", own, true))
        assertFalse(s.applyEqCorrection("AutoEq: B", fixed, null, List(10) { 0 }, true))
        assertEquals(own, s.eqLevels.value)
        // trying a second headphone after the first keeps the first own curve, not the first correction
        assertTrue(s.applyEqCorrection("AutoEq: A", fixed, null, own, true))
        assertTrue(s.applyEqCorrection("AutoEq: B", List(10) { -100 }, "AutoEq: A", fixed, true))
        s.restoreEqBefore()
        assertEquals(own, s.eqLevels.value)
    }

    @Test fun backToMyCurveKeepsTheEqualizerOffIfItWasOff() {
        val s = Settings(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("eq-off", 0))
        val own = listOf(100, 0, 0, 0, 0, 0, 0, 0, 0, -50)
        s.setEq(null, own); s.setEqOn(false)
        assertTrue(s.applyEqCorrection("AutoEq: A", List(10) { 200 }, null, own, false))
        assertEquals(true, s.eqOn.value)
        s.restoreEqBefore()
        assertEquals(false, s.eqOn.value)
        assertEquals(own, s.eqLevels.value)
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
