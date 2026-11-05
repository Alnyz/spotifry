package com.spotifry.music.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmartTrimTest {
    private val len = 252_000L // 4:12

    @Test fun talkAtStartAndEndBecomesTheTrim() {
        val t = smartTrimOf(listOf(Segment(0, 4_013, len), Segment(222_313, 252_241, len)), len)
        assertEquals(Trim(4_013, 222_313), t)
    }

    @Test fun onlyTheStartOrOnlyTheEnd() {
        assertEquals(Trim(13_223), smartTrimOf(listOf(Segment(0, 13_223)), len))
        assertEquals(Trim(endMs = 240_000), smartTrimOf(listOf(Segment(240_000, 251_500)), len))
    }

    @Test fun middlePartsAndOtherCutsAreIgnored() {
        assertNull(smartTrimOf(listOf(Segment(40_694, 47_133, len)), len))
        // marked on a video a minute longer: the times don't fit this one
        assertNull(smartTrimOf(listOf(Segment(0, 20_000, len + 60_000)), len))
    }

    @Test fun tooLittleLeftIsNoTrim() {
        assertNull(smartTrimOf(listOf(Segment(0, 245_000)), len))
        assertNull(smartTrimOf(listOf(Segment(0, 10_000)), 0))
    }

    @Test fun ownTrimWins() {
        val own = Trim(5_000)
        val smart = Trim(12_000, 200_000)
        assertEquals(own, trimFor(own, smart, true))
        assertEquals(smart, trimFor(null, smart, true))
        assertNull(trimFor(null, smart, false))
    }

    @Test fun readsOnlyThisVideosNonMusicParts() {
        val json = """[
            {"videoID":"other","segments":[{"segment":[0,30],"category":"music_offtopic","actionType":"skip","videoDuration":200}]},
            {"videoID":"9bZkp7q19f0","segments":[
                {"segment":[0,4.013],"UUID":"a","category":"music_offtopic","actionType":"skip","locked":0,"votes":3,"videoDuration":252.241,"description":""},
                {"segment":[222.313,252.241],"UUID":"b","category":"music_offtopic","actionType":"skip","locked":0,"votes":1,"videoDuration":0,"description":""},
                {"segment":[10,20],"UUID":"c","category":"sponsor","actionType":"skip","videoDuration":252.241}
            ]}
        ]"""
        assertEquals(listOf(Segment(0, 4_013, 252_241), Segment(222_313, 252_241, 0)), parseSegments(json, "9bZkp7q19f0"))
        assertEquals(emptyList<Segment>(), parseSegments(json, "missing"))
    }

    @Test fun offStaysOffAndPhoneSongsAreNeverAsked() = runBlocking {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("smart", 0)
        prefs.edit().putString("a", "4013,222313").apply()
        // both lookups below stop before asking, so no request is made
        val s = SmartTrims(prefs, OkHttpClient())
        assertEquals(Trim(4_013, 222_313), s["a"])
        s.turnOff("a")
        assertNull(s["a"])
        s.lookUp(Song("a", "A", "B", 252, null))
        assertNull(s["a"])
        s.lookUp(Song("local:1", "A", "B", 252, null))
        assertEquals(false, prefs.contains("local:1"))
    }
}
