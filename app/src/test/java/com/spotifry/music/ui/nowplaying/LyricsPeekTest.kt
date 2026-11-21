package com.spotifry.music.ui.nowplaying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import com.spotifry.music.data.LyricLine
import com.spotifry.music.data.Lyrics
import com.spotifry.music.data.testSong
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.player.Trim
import com.spotifry.music.ui.theme.MusicTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun lines(vararg text: String) = text.mapIndexed { i, t -> LyricLine(1_000L + i * 4_000L, t) }

class PeekLineTest {
    private val three = Lyrics.Timed(lines("one", "two", "three"))

    @Test fun beforeTheFirstLineTheFirstLineShows() = assertEquals("one", peekLine(three, 0))
    @Test fun theLineBeingSungShows() = assertEquals("two", peekLine(three, 5_500))
    @Test fun afterTheLastTheLastStays() = assertEquals("three", peekLine(three, 99_000))
    @Test fun aBlankLineShowsANote() = assertEquals("♪", peekLine(Lyrics.Timed(listOf(LyricLine(0, ""))), 10))
    @Test fun plainLyricsShowTheirFirstLine() = assertEquals("first", peekLine(Lyrics.Plain(listOf("", "first", "second")), 0))
    @Test fun noLyricsNoLine() {
        assertNull(peekLine(null, 0))
        assertNull(peekLine(Lyrics.Timed(emptyList()), 0))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LyricsPeekTest {
    @get:Rule val compose = createComposeRule()
    private val song = LyricsUi.Ready(Lyrics.Timed(lines("one", "two", "three")))
    private var opened = 0
    private var retried = 0
    private var at by mutableStateOf(0L)
    private var ui by mutableStateOf<LyricsUi>(song)

    private fun show() = compose.setContent { MusicTheme { LyricsPeek(ui, { at }, { opened++ }, { retried++ }) } }

    @Test fun theLineFollowsTheSong() {
        at = 5_500; show()
        compose.onNodeWithText("two", useUnmergedTree = true).assertIsDisplayed()
        at = 9_500; compose.waitForIdle()
        compose.onNodeWithContentDescription("Show all lyrics. three").assertIsDisplayed()
    }

    @Test fun theRowKeepsItsHeightWhateverTheLyricsAre() {
        show()
        val heights = ArrayList<Dp>()
        listOf(song, LyricsUi.None, LyricsUi.Loading, LyricsUi.Failed, LyricsUi.Ready(Lyrics.Plain(listOf("a", "b")))).forEach {
            ui = it; compose.waitForIdle()
            heights += compose.onNodeWithTag("lyrics peek").getUnclippedBoundsInRoot().let { b -> b.bottom - b.top }
        }
        assertEquals(1, heights.toSet().size)
    }

    @Test fun oneTapTargetWithAButtonRoleOpensLyricsMode() {
        at = 5_500; show()
        compose.onNodeWithContentDescription("Show all lyrics. two")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assert(hasClickAction()).performClick()
        assertEquals(1, opened)
    }

    @Test fun failedLyricsCanBeTriedAgain() {
        ui = LyricsUi.Failed; show()
        compose.onNodeWithTag("lyrics peek").performClick()
        assertEquals(1, retried)
        assertEquals(0, opened)
    }

    @Test fun withoutLyricsAQuietRowStillOpensLyricsMode() {
        ui = LyricsUi.None; show()
        compose.onNodeWithText("Lyrics").assertIsDisplayed()
        compose.onNodeWithTag("lyrics peek").performClick()
        assertEquals(1, opened)
    }

    // lyrics are timed to the whole song, the player's position to the kept part: the line adds the trim's start
    private val longSong = LyricsUi.Ready(Lyrics.Timed(List(8) { LyricLine(it * 60_000L, "line $it") }))

    @Test @Config(qualifiers = "w411dp-h914dp")
    fun theLyricsFollowTheWholeSongNotTheTrimmedPart() {
        compose.mainClock.autoAdvance = false
        val state = PlayerUiState(current = testSong, isPlaying = true, durationMs = 200_000, queue = listOf(testSong), trim = Trim(startMs = 5 * 60_000L))
        compose.setContent { MusicTheme { NowPlayingContent(state, SaveUi.Unsaved, {}, {}, {}, {}, {}, {}, {}, {}, {}, lyrics = longSong, position = { 2_000L }) } }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithContentDescription("Show all lyrics. line 5").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("line 0", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test @Config(qualifiers = "w411dp-h914dp")
    fun titleAndControlsStayPutWithOrWithoutLyrics() {
        compose.mainClock.autoAdvance = false
        var now by mutableStateOf<LyricsUi>(longSong)
        val state = PlayerUiState(current = testSong, isPlaying = true, durationMs = 200_000, queue = listOf(testSong))
        compose.setContent { MusicTheme { NowPlayingContent(state, SaveUi.Unsaved, {}, {}, {}, {}, {}, {}, {}, {}, {}, lyrics = now) } }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        fun tops() = listOf("Next song", "Save on this phone").map { compose.onNodeWithContentDescription(it).getUnclippedBoundsInRoot().top } + compose.onNodeWithText("Title").getUnclippedBoundsInRoot().top
        val withLyrics = tops()
        for (other in listOf(LyricsUi.None, LyricsUi.Loading, LyricsUi.Failed)) {
            now = other
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            assertEquals(withLyrics, tops())
        }
    }

    // nothing scrolls, so on a short phone with large text the cover gives way and the bottom row still fits
    @Test @Config(qualifiers = "w360dp-h640dp", fontScale = 1.3f)
    fun everythingFitsOnAShortPhoneWithLargeText() {
        compose.mainClock.autoAdvance = false
        val state = PlayerUiState(current = testSong, isPlaying = true, durationMs = 200_000, queue = listOf(testSong), quality = "Lossless")
        compose.setContent { MusicTheme { NowPlayingContent(state, SaveUi.Unsaved, {}, {}, {}, {}, {}, {}, {}, {}, {}, lyrics = longSong, note = "A note") } }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val screen = compose.onRoot().getUnclippedBoundsInRoot().bottom
        assertTrue(compose.onNodeWithContentDescription("Queue").getUnclippedBoundsInRoot().bottom <= screen)
        assertTrue(compose.onNodeWithText("Title").getUnclippedBoundsInRoot().top > compose.onNodeWithText("Now playing").getUnclippedBoundsInRoot().bottom)
    }
}
