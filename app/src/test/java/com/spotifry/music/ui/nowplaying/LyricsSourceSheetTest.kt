package com.spotifry.music.ui.nowplaying

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.spotifry.music.data.LyricLine
import com.spotifry.music.data.LyricWord
import com.spotifry.music.data.Lyrics
import com.spotifry.music.ui.nowplaying.NowPlayingViewModel.SourceRow
import com.spotifry.music.ui.theme.MusicTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LyricsSourceSheetTest {
    @get:Rule val compose = createComposeRule()

    private val word = Lyrics.Timed(listOf(LyricLine(0, "a", listOf(LyricWord(0, 5, "a")))), Lyrics.KUGOU)
    private val line = Lyrics.Timed(listOf(LyricLine(0, "a")), Lyrics.LRCLIB)
    private val rows = listOf(
        SourceRow(Lyrics.KUGOU, word, false), SourceRow(Lyrics.LRCLIB, line, false),
        SourceRow(Lyrics.DEEZER, null, true), SourceRow(Lyrics.NETEASE, null, false), SourceRow(Lyrics.UNISON, null, false),
    )

    private fun show(pick: String?, picked: MutableList<String?>) =
        compose.setContent { MusicTheme { LyricsSourceSheet(rows, pick, "KuGou", { picked += it }, {}) } }

    @Test fun sourcesAreGroupedAndTheMissingOnesFoldAway() {
        show(null, mutableListOf())
        compose.onNodeWithText("Word by word").assertIsDisplayed()
        compose.onNodeWithText("Line by line").assertIsDisplayed()
        // the test window is small and the sheet scrolls: the lower rows are there, scrolled to before use
        compose.onNodeWithText("Checking…").assertExists()
        compose.onNodeWithText("The best match. Now KuGou.").assertExists()
        compose.onNodeWithText("Not found for this song (2)").assertExists()
        compose.onNodeWithText("Nothing for this song").assertDoesNotExist()
        compose.onNodeWithText("Not found for this song (2)").performScrollTo().performClick()
        compose.onAllNodesWithTextCount("Nothing for this song", 2)
    }

    @Test fun tappingASourceChoosesItAndAutomaticClearsThePick() {
        val picked = mutableListOf<String?>()
        show(Lyrics.LRCLIB, picked)
        compose.onNodeWithText(Lyrics.KUGOU).performClick()
        compose.onNodeWithText("Automatic").performClick()
        assertEquals(listOf<String?>(Lyrics.KUGOU, null), picked)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String, n: Int) =
        assertEquals(n, onAllNodesWithText(text).fetchSemanticsNodes().size)
}
