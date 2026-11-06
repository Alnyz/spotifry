package com.spotifry.music.ui.nowplaying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
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

class PeekLinesTest {
    private val three = lines("one", "two", "three")

    @Test fun beforeTheFirstLineOnlyTheNextSlotIsFilledWithLineOne() = assertEquals(PeekLines(null, null, "one"), peekLines(three, -1))
    @Test fun theFirstLineHasNothingBeforeIt() = assertEquals(PeekLines(null, "one", "two"), peekLines(three, 0))
    @Test fun inTheMiddleAllThreeAreFilled() = assertEquals(PeekLines("one", "two", "three"), peekLines(three, 1))
    @Test fun afterTheLastTheLastStaysCurrentAndNextIsEmpty() = assertEquals(PeekLines("two", "three", null), peekLines(three, 2))
    @Test fun aSongOfOneLine() {
        val one = lines("only")
        assertEquals(PeekLines(null, null, "only"), peekLines(one, -1))
        assertEquals(PeekLines(null, "only", null), peekLines(one, 0))
    }
    @Test fun anIndexOutOfRangeIsHeldToTheEnds() {
        assertEquals(PeekLines("two", "three", null), peekLines(three, 99))
        assertEquals(PeekLines(null, null, "one"), peekLines(three, -5))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LyricsPeekTest {
    @get:Rule val compose = createComposeRule()
    private val song = LyricsUi.Ready(Lyrics.Timed(lines("one", "two", "three")))
    private var opened = 0
    private var at by mutableStateOf(0L)
    private var ui by mutableStateOf<LyricsUi>(song)

    private fun show() = compose.setContent { MusicTheme { LyricsPeek(ui, { at }, { opened++ }) } }
    private fun top(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot().top
    private fun gone(text: String) = assertTrue("$text shown", compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

    @Test fun beforeTheFirstLineJustLineOneAsNext() {
        show()
        compose.onNodeWithText("one", useUnmergedTree = true).assertIsDisplayed()
        gone("two"); gone("three")
    }

    @Test fun inTheMiddleThePreviousCurrentAndNextStackInThatOrder() {
        at = 5_500; show()
        assertTrue(top("one") < top("two") && top("two") < top("three"))
    }

    @Test fun theLineMovesWithTheSongAndAfterTheLastItStays() {
        show()
        at = 1_500; compose.waitForIdle()
        compose.onNodeWithText("one", useUnmergedTree = true).assertIsDisplayed(); compose.onNodeWithText("two", useUnmergedTree = true).assertIsDisplayed(); gone("three")
        at = 60_000; compose.waitForIdle()
        gone("one"); compose.onNodeWithText("two", useUnmergedTree = true).assertIsDisplayed(); compose.onNodeWithText("three", useUnmergedTree = true).assertIsDisplayed()
        // the current line is the lower of the two: nothing under it
        assertTrue(top("two") < top("three"))
    }

    @Test fun anotherSongReplacesAllThreeLines() {
        at = 5_500; show()
        ui = LyricsUi.Ready(Lyrics.Timed(listOf(LyricLine(0, "new"), LyricLine(9_000, "newer"))))
        compose.waitForIdle()
        gone("one"); gone("three")
        compose.onNodeWithText("new", useUnmergedTree = true).assertIsDisplayed(); compose.onNodeWithText("newer", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun theSlotKeepsItsHeightWhateverTheLyricsAre() {
        show()
        val heights = ArrayList<Dp>()
        listOf(song, LyricsUi.None, LyricsUi.Loading, LyricsUi.Failed, LyricsUi.Ready(Lyrics.Plain(listOf("a", "b"))), LyricsUi.Ready(Lyrics.Timed(emptyList()))).forEach {
            ui = it; compose.waitForIdle()
            heights += compose.onNodeWithTag("lyrics peek").getUnclippedBoundsInRoot().height
        }
        assertEquals(1, heights.toSet().size)
        assertTrue("three lines tall: ${heights[0]}", heights[0] > 60.dp)
    }

    @Test fun withoutTimedLyricsNothingShowsAndNothingIsTappable() {
        ui = LyricsUi.Ready(Lyrics.Plain(listOf("not timed")))
        show()
        gone("not timed")
        assertTrue(compose.onAllNodesWithText("Show all lyrics", substring = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("lyrics peek").performClick()
        assertEquals(0, opened)
    }

    @Test fun oneTapTargetWithAButtonRoleOpensAllLyricsAndReadsTheCurrentLine() {
        at = 5_500; show()
        val block = compose.onNodeWithContentDescription("Show all lyrics. two")
        block.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assert(hasClickAction())
        block.performClick()
        assertEquals(1, opened)
    }

    @Test fun theLineBeingReadFollowsTheSongWithoutRebuildingTheBlock() {
        show()
        at = 5_500; compose.waitForIdle()
        compose.onNodeWithContentDescription("Show all lyrics. two").assertIsDisplayed()
        at = 9_500; compose.waitForIdle()
        compose.onNodeWithContentDescription("Show all lyrics. three").assertIsDisplayed()
    }

    // lyrics are timed to the whole song, the player's position to the kept part: the block adds the trim's start, like the card under it
    private val longSong = LyricsUi.Ready(Lyrics.Timed(List(8) { LyricLine(it * 60_000L, "line $it") }))

    @Test @Config(qualifiers = "w411dp-h914dp")
    fun theLyricsFollowTheWholeSongNotTheTrimmedPart() {
        compose.mainClock.autoAdvance = false
        val state = PlayerUiState(current = testSong, isPlaying = true, durationMs = 200_000, queue = listOf(testSong), trim = Trim(startMs = 5 * 60_000L))
        compose.setContent { MusicTheme { NowPlayingContent(state, SaveUi.Unsaved, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, lyrics = longSong, position = { 2_000L }) } }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        // 2 s into a song cut at 5:00 is 5:02: line 5 as the current line in the block and in the card; line 0 nowhere
        assertEquals("block and card", 2, compose.onAllNodesWithText("line 5", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue(compose.onAllNodesWithText("line 0", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("Show all lyrics. line 5").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w411dp-h914dp")
    fun titleAndControlsStayPutWithOrWithoutLyrics() {
        compose.mainClock.autoAdvance = false
        var now by mutableStateOf<LyricsUi>(longSong)
        val state = PlayerUiState(current = testSong, isPlaying = true, durationMs = 200_000, queue = listOf(testSong))
        compose.setContent { MusicTheme { NowPlayingContent(state, SaveUi.Unsaved, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, lyrics = now) } }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        fun tops() = listOf("Next song", "Save on this phone").map { compose.onNodeWithContentDescription(it).getUnclippedBoundsInRoot().top } + compose.onNodeWithText("Title").getUnclippedBoundsInRoot().top
        val withLyrics = tops()
        for (other in listOf(LyricsUi.None, LyricsUi.Loading, LyricsUi.Failed)) {
            now = other
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            assertEquals(withLyrics, tops())
        }
    }

    @Test @Config(fontScale = 2f)
    fun largeTextCountsEachLinesOwnGrowth() {
        var growth = 0.dp
        compose.setContent { growth = androidx.compose.ui.platform.LocalDensity.current.textGrowth() }
        compose.waitForIdle()
        // scaling is not linear: the title, artist and time lines grow about 33 dp here, the three lyric lines about the same again
        assertEquals(66f, growth.value, 8f)
    }

    @Test @Config(fontScale = 2f)
    fun theBlockGrowsWithTextSize() {
        show()
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("lyrics peek").getUnclippedBoundsInRoot().height > 90.dp)
    }

    // Robolectric's text metrics differ from a phone's (the controls measured 95 dp lower than on the emulator), so the real
    // layout can't be asserted here; the rule's numbers are, and they were checked by eye on 360x640 (see the commit)
    @Test fun theCoverRuleKeepsRoomForEverythingElseOnAShortPhone() {
        // 360x640 with 24 dp bars at both ends: 640 - (378 + 48) = 214 dp, the size seen on the emulator
        assertEquals(214.dp, coverSize(360.dp, 640.dp, 0.dp, 48.dp, false))
        // a tall phone is held to its width, and a wide, short window to 44% of the height
        assertEquals(364.dp, coverSize(412.dp, 914.dp, 0.dp, 48.dp, false))
        assertEquals(.44f * 914, coverSize(800.dp, 914.dp, 0.dp, 48.dp, false).value, .1f)
        // larger text and the pills row take more
        assertTrue(coverSize(360.dp, 640.dp, 33.dp, 48.dp, true) < coverSize(360.dp, 640.dp, 0.dp, 48.dp, false) - 60.dp)
        // never smaller than 140 dp
        assertEquals(140.dp, coverSize(360.dp, 300.dp, 0.dp, 0.dp, false))
    }
}
