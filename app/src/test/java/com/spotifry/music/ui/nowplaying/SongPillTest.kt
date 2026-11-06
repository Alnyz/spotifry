package com.spotifry.music.ui.nowplaying

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.LyricLine
import com.spotifry.music.data.Lyrics
import com.spotifry.music.data.testSong
import androidx.media3.common.Player
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SongPillTest {
    @get:Rule val compose = createComposeRule()
    private val timed = LyricsUi.Ready(Lyrics.Timed(listOf(LyricLine(1_000, "first"), LyricLine(5_000, "second"))))

    @Test fun theLineBeingSungAndTheFirstBeforeItStarts() {
        assertEquals("first", pillLabel(timed, "Other", { 0L }))
        assertEquals("first", pillLabel(timed, "Other", { 4_999L }))
        assertEquals("second", pillLabel(timed, "Other", { 90_000L }))
    }

    @Test fun untimedLyricsAreOfferedNotQuoted() {
        assertEquals("Show lyrics", pillLabel(LyricsUi.Ready(Lyrics.Plain(listOf("a line"))), null, { 0L }))
    }

    @Test fun noLyricsShowsTheNextSong() {
        assertEquals("Next: Other", pillLabel(LyricsUi.None, "Other", { 0L }))
        assertEquals("Next: Other", pillLabel(LyricsUi.Failed, "Other", { 0L }))
    }

    @Test fun nothingToSayOrStillLookingShowsNothing() {
        assertNull(pillLabel(LyricsUi.None, null, { 0L }))
        assertNull(pillLabel(LyricsUi.Failed, null, { 0L }))
        assertNull(pillLabel(LyricsUi.Loading, "Other", { 0L }))
    }

    @Test fun theNextSongIsWhereNextGoes() {
        val a = testSong.copy(id = "a", title = "A"); val b = testSong.copy(id = "b", title = "B"); val c = testSong.copy(id = "c", title = "C")
        val q = listOf(a, b, c)
        assertEquals("B", PlayerUiState(queue = q, currentIndex = 0, nextIndex = 1).nextTitle())
        // shuffle: the player's own pick, not list order
        assertEquals("C", PlayerUiState(queue = q, currentIndex = 0, nextIndex = 2, order = listOf(0, 2, 1)).nextTitle())
        // repeat all on the last song wraps to the first
        assertEquals("A", PlayerUiState(queue = q, currentIndex = 2, nextIndex = 0, repeatMode = Player.REPEAT_MODE_ALL).nextTitle())
        // the end of the queue, and a lone song on repeat all, have nothing next
        assertNull(PlayerUiState(queue = q, currentIndex = 2).nextTitle())
        assertNull(PlayerUiState(queue = listOf(a), currentIndex = 0, nextIndex = 0, repeatMode = Player.REPEAT_MODE_ALL).nextTitle())
    }

    private var lyricsOpened = 0
    private var queueOpened = 0

    private fun show(lyrics: LyricsUi, next: String? = "Other", position: () -> Long = { 0L }) = compose.setContent {
        MusicTheme { SongPill(lyrics, next, position, { lyricsOpened++ }, { queueOpened++ }) }
    }

    @Test fun aTapOnALyricOpensTheLyrics() {
        show(timed)
        compose.onNodeWithText("first").performClick()
        assertEquals(1 to 0, lyricsOpened to queueOpened)
    }

    @Test fun aTapOnTheNextSongOpensTheQueue() {
        show(LyricsUi.None)
        compose.onNodeWithText("Next: Other").performClick()
        assertEquals(0 to 1, lyricsOpened to queueOpened)
    }

    @Test fun aSwipeEitherWayOpensTheQueueOnce() {
        show(timed)
        compose.onNodeWithText("first").performTouchInput { swipeLeft() }
        assertEquals(0 to 1, lyricsOpened to queueOpened)
        compose.onNodeWithText("first").performTouchInput { swipeRight() }
        assertEquals(0 to 2, lyricsOpened to queueOpened)
    }

    @Test fun scrollingThePageOverThePillOpensNothing() {
        compose.setContent {
            MusicTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SongPill(timed, "Other", { 0L }, { lyricsOpened++ }, { queueOpened++ })
                    Spacer(Modifier.height(3_000.dp))
                }
            }
        }
        compose.onNodeWithText("first").performTouchInput { swipeUp() }
        assertEquals(0 to 0, lyricsOpened to queueOpened)
    }

    // the whole screen with untimed lyrics: the card's word timer for timed ones runs every frame, and these tests wait for idle
    private val untimed = LyricsUi.Ready(Lyrics.Plain(listOf("a line")))

    private fun showScreen(lyrics: LyricsUi, queue: List<com.spotifry.music.data.source.Song>) {
        val state = PlayerUiState(current = queue[0], isPlaying = true, durationMs = 200_000, queue = queue)
        compose.setContent {
            MusicTheme {
                NowPlayingContent(
                    state, SaveUi.Unsaved, {}, {}, {}, {}, {}, {}, {}, {}, { queueOpened++ }, {}, lyrics = lyrics,
                    onShowLyrics = { if (it) lyricsOpened++ },
                )
            }
        }
    }

    @Test @Config(qualifiers = "w411dp-h914dp")
    fun onTheScreenATapOpensLyricsAndASwipeTheQueue() {
        showScreen(untimed, listOf(testSong))
        compose.onNodeWithText("Show lyrics").performClick()
        assertEquals(1 to 0, lyricsOpened to queueOpened)
        compose.onNodeWithText("Show lyrics").performTouchInput { swipeLeft() }
        assertEquals(1 to 1, lyricsOpened to queueOpened)
    }

    @Test @Config(qualifiers = "w411dp-h914dp")
    fun theControlsStayPutWithOrWithoutThePill() {
        val tops = mutableMapOf<String, Dp>()
        var ui by mutableStateOf<LyricsUi>(untimed)
        val state = PlayerUiState(current = testSong, isPlaying = true, durationMs = 200_000, queue = listOf(testSong))
        compose.setContent { MusicTheme { NowPlayingContent(state, SaveUi.Unsaved, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, lyrics = ui) } }
        tops["with"] = compose.onNodeWithContentDescription("Next song").getUnclippedBoundsInRoot().top
        ui = LyricsUi.None
        compose.waitForIdle()
        // no lyrics and nothing next: no pill, and everything above it where it was
        assertTrue(compose.onAllNodesWithText("Show lyrics").fetchSemanticsNodes().isEmpty())
        tops["without"] = compose.onNodeWithContentDescription("Next song").getUnclippedBoundsInRoot().top
        assertEquals(tops["with"], tops["without"])
    }

    @Test fun theLineMovesWithTheSong() {
        val at = mutableLongStateOf(0L)
        val position = { at.longValue }
        show(timed, position = position)
        compose.onNodeWithText("first").assertIsDisplayed()
        at.longValue = 6_000
        compose.onNodeWithText("second").assertIsDisplayed()
    }

    /** Eight lines a minute apart, so the line sung at any time is easy to tell. */
    private val longSong = LyricsUi.Ready(Lyrics.Timed(List(8) { LyricLine(it * 60_000L, "line $it") }))

    // the lyrics are timed to the whole song, the player's position to the kept part: both pill and card must add the trim's start
    @Test @Config(qualifiers = "w411dp-h914dp")
    fun theLyricsFollowTheWholeSongNotTheTrimmedPart() {
        compose.mainClock.autoAdvance = false
        val state = PlayerUiState(current = testSong, isPlaying = true, durationMs = 200_000, queue = listOf(testSong), trim = Trim(startMs = 5 * 60_000L))
        compose.setContent { MusicTheme { NowPlayingContent(state, SaveUi.Unsaved, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, lyrics = longSong, position = { 2_000L }) } }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        // 2 s into a song cut at 5:00 is 5:02: line 5, in the pill and in the card. Position alone would say line 0.
        assertEquals(2, compose.onAllNodesWithText("line 5").fetchSemanticsNodes().size)
        assertTrue(compose.onAllNodesWithText("line 0").fetchSemanticsNodes().isEmpty())
    }
}
