package com.spotifry.music.ui.listening

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.ListeningStats
import com.spotifry.music.data.db.ListenRow
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.listeningStats
import com.spotifry.music.ui.theme.MusicTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp")
class ListeningScreenTest {
    @get:Rule val rule = createComposeRule()
    private val zone = ZoneOffset.UTC
    private val now = at(2027, 3, 11, 21, 44)
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).toInstant(zone).toEpochMilli()
    private fun row(w: Long, id: String, s: Int, artist: String = "Artist $id", skipped: Boolean = false) = ListenRow(w, id, s, skipped, SongEntity(id, "Song $id", artist, 200, null, null, null, null))
    private var artist: String? = null
    private var played: List<String>? = null
    private var opened = false

    private fun show(vararg r: ListenRow) {
        val stats: ListeningStats = listeningStats(r.toList(), now, zone)!!
        rule.setContent {
            MusicTheme {
                ListeningContent(stats, emptySet(), false, {}, { l, _, _ -> played = l.map { it.id } }, {}, { artist = it }, { opened = true }, 0.dp)
            }
        }
    }
    private fun scrollTo(text: String) { rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text)); rule.onNodeWithText(text).assertIsDisplayed() }
    private fun missing(text: String) = rule.onNodeWithText(text).assertDoesNotExist()

    @Test fun tasteHasASpotifriedButton() {
        show(row(now - 240_000, "a", 240))
        rule.onNodeWithContentDescription("Spotifried 2027, reveal my year").assertIsDisplayed().assertHasClickAction().performClick()
        assertEquals(true, opened)
    }

    @Test fun theGateSitsUnderTheTitleAboveTheSun() {
        show(row(now - 240_000, "a", 240))
        val title = rule.onNodeWithText("First spark.").fetchSemanticsNode().boundsInRoot.bottom
        val gate = rule.onNodeWithContentDescription("Spotifried 2027, reveal my year").fetchSemanticsNode().boundsInRoot
        val sun = rule.onNodeWithContentDescription("Your listening by hour of the day. Most around 9 p.m.").fetchSemanticsNode().boundsInRoot.top
        assertTrue(gate.top >= title && gate.bottom <= sun)
        assertTrue(gate.height >= 48f)
    }

    @Test fun theGateStillOpensWhenAnimationsAreOff() {
        Settings.Global.putFloat(ApplicationProvider.getApplicationContext<Context>().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        show(row(now - 240_000, "a", 240))
        rule.onNodeWithContentDescription("Spotifried 2027, reveal my year").performClick()
        assertEquals(true, opened)
    }

    @Test fun theGateIsOneButtonWithItsDecorHiddenFromTalkBack() {
        show(row(now - 240_000, "a", 240))
        rule.onNodeWithContentDescription("Spotifried 2027, reveal my year").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
        assertTrue(rule.onAllNodesWithText("Spotifried").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("2027").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("YOUR YEAR, IN MUSIC.").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("REVEAL MY YEAR").fetchSemanticsNodes().isEmpty())
    }

    @Test fun theGateIsAbout224TallAtBaseText() {
        show(row(now - 240_000, "a", 240))
        val gate = rule.onNodeWithContentDescription("Spotifried 2027, reveal my year").fetchSemanticsNode().boundsInRoot
        // never shorter than 224 / 342 of a 320 wide card; the test font runs a little taller than a phone's, so allow some growth
        val base = 320f * 224f / 342f * rule.density.density
        assertTrue(gate.height >= base - 2f && gate.height <= base * 1.35f)
    }

    @Test fun theLightRunsOnceEvery5Point5Seconds() {
        assertEquals(0f, lightRun(0), 0f); assertEquals(0f, lightRun(LIGHT_WAIT_MS - 1), 0f)
        assertTrue(lightRun(LIGHT_WAIT_MS + LIGHT_RUN_MS / 2) in .1f..1f)
        assertEquals(0f, lightRun(LIGHT_WAIT_MS + LIGHT_RUN_MS + 1), 0f) // dark between runs, so nothing redraws
        assertEquals(lightRun(LIGHT_WAIT_MS + 300), lightRun(LIGHT_WAIT_MS + LIGHT_EVERY_MS * 3 + 300), 1e-6f)
    }

    @Test @Config(fontScale = 2f) fun theGateKeepsItsSizeAndNameAtBigText() {
        show(row(now - 240_000, "a", 240))
        val gate = rule.onNodeWithContentDescription("Spotifried 2027, reveal my year").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(gate.height >= 48f * rule.density.density && gate.right <= 360f * rule.density.density)
        // the content pushes the card past its base height instead of being clipped
        assertTrue(gate.height > 320f * 224f / 342f * rule.density.density + 1f)
    }

    @Test fun theRecordComesOutTurnsIntoPlaceThenGlints() {
        val start = vinylPose(0f)
        assertEquals(96f, start.shift, .01f); assertEquals(-150f, start.turn, .01f); assertEquals(0f, start.glintAlpha, .01f)
        assertEquals(0f, vinylPose(PULL_MS).shift, .01f)
        assertEquals(0f, vinylPose(1000f).glintAlpha, .01f) // the glint waits until the record has nearly settled
        assertTrue(vinylPose(PULL_MS).glintAlpha > .5f)
        for (rest in listOf(vinylPose(OPEN_MS), vinylPose(OPEN_MS * 5))) {
            assertEquals(0f, rest.shift, .001f); assertEquals(0f, rest.turn, .001f)
            assertEquals(.85f, rest.glintAlpha, .001f); assertEquals(1f, rest.glintScale, .001f); assertEquals(0f, rest.glintTurn, .001f)
        }
    }

    @Test fun withAnimationsOffTheGateIsStillAtRest() {
        Settings.Global.putFloat(ApplicationProvider.getApplicationContext<Context>().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        rule.mainClock.autoAdvance = false
        show(row(now - 240_000, "a", 240))
        // nothing is waiting for a frame: no pull-out, no glint, no turning label
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val recomposer = rule.runOnIdle { androidx.compose.runtime.Recomposer.runningRecomposers.value.single() }
        assertEquals(false, recomposer.hasPendingWork)
    }

    @Test fun firstDayIsASparkWithNoTabsNoDaysAndOneFirstSong() {
        show(row(now - 240_000, "a", 240))
        rule.onNodeWithText("First spark.").assertIsDisplayed()
        rule.onNodeWithText("4 minutes in. This grows as you listen.").assertIsDisplayed()
        missing("So far")
        missing("Every day you've played")
        scrollTo("Your top songs show up once you've heard three.")
        scrollTo("It started here")
        missing("Kept only on this phone.")
        missing("You keep coming back")
    }

    @Test fun aFewDaysInShowTheSongsAndWaitForTheArtists() {
        val r = listOf("a", "b", "c").mapIndexed { i, id -> row(at(2027, 3, 9, 21, i), id, 300, artist = "Same") }.toTypedArray()
        show(*r)
        rule.onNodeWithText("Evening listener.").assertIsDisplayed()
        missing("So far")
        scrollTo("You keep coming back")
        scrollTo("Your top artists show up once you've heard three.")
        scrollTo("Every day you've played")
        missing("The ones you play most")
    }

    @Test fun anEmptyWeekOpensOnTheMonthAndTappingItSaysSo() {
        show(row(at(2027, 1, 2, 23), "a", 600), row(at(2027, 3, 2, 23), "b", 600))
        rule.onNodeWithText("Night listener.").assertIsDisplayed()
        rule.onNodeWithText("This week").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Still warm.").assertIsDisplayed()
        rule.onNodeWithText("Nothing yet this week. Play something.").assertIsDisplayed()
        missing("ads. Not one.")
        scrollTo("Every day you've played")
    }

    @Test fun aPeriodChangesTheTitleAndTheLists() {
        show(
            row(at(2027, 1, 2, 1), "old1", 9000), row(at(2027, 1, 2, 1, 5), "old2", 8000), row(at(2027, 1, 2, 1, 9), "old3", 7000),
            row(at(2027, 3, 9, 21), "new1", 3000), row(at(2027, 3, 9, 21, 5), "new2", 2000), row(at(2027, 3, 9, 21, 9), "new3", 1000),
        )
        rule.onNodeWithText("Evening listener.").assertIsDisplayed()
        scrollTo("Song new1")
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("All time"))
        rule.onNodeWithText("All time").performClick()
        rule.waitForIdle()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Night listener."))
        rule.onNodeWithText("Night listener.").assertIsDisplayed()
        scrollTo("Song old1")
    }

    @Test fun theTallyShowsPlaysSongsAndArtistsInOneRowButNoJokes() {
        show(row(now - 3000, "a", 60), row(now - 2000, "b", 10, skipped = true), row(now - 1000, "c", 10, skipped = true))
        scrollTo("play")
        scrollTo("song")
        scrollTo("artist")
        scrollTo("day")
        // the tally is one item, so with its lines on screen an absent line is really gone, not just scrolled away
        missing("ads. Not one.")
        missing("skips. Nobody stopped you.")
        missing("skip. Nobody stopped you.")
    }

    @Test fun tappingAnArtistSearchesForTheName() {
        val r = (1..3).map { row(now - it * 100_000L, "s$it", 400 - it, artist = "Band $it") }.toTypedArray()
        show(*r)
        // the first artist's name is also on the no. 1 card, so the second is the one only the list has
        scrollTo("Band 2")
        rule.onNodeWithText("Band 2").performClick()
        assertEquals("Band 2", artist)
    }

    @Test fun theNumberOnePlaysAllTheTopSongs() {
        val r = (1..3).map { row(now - it * 100_000L, "s$it", 400 - it) }.toTypedArray()
        show(*r)
        scrollTo("Your no. 1")
        rule.onNodeWithText("Your no. 1").performClick()
        assertEquals(listOf("s1", "s2", "s3"), played)
    }

    @Test fun tappingTheSunShowsThatHourAndTappingTheMiddleGoesBack() {
        show(row(at(2027, 3, 10, 1), "a", 600), row(at(2027, 3, 10, 13), "b", 60))
        val sun = rule.onNodeWithContentDescription("Your listening by hour of the day", substring = true)
        sun.performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .5f, height * .98f)) } // the bottom: noon, 12 p.m.
        rule.waitForIdle()
        rule.onNodeWithText("12 p.m.").assertExists()
        sun.performTouchInput { click(center) }
        rule.waitForIdle()
        missing("12 p.m.")
    }

    @Test fun anEmptyMonthTabDoesNotSayWeek() {
        // Wednesday the 3rd of a month that began on a Monday would merge, so: Thursday 11 March, heard only last month and this week
        show(row(at(2027, 2, 2, 23), "a", 600), row(at(2027, 3, 9, 23), "b", 600))
        rule.onNodeWithText("Night listener.").assertIsDisplayed()
        assertEquals("Nothing yet this month. Play something.", listenerLine(com.spotifry.music.data.Period(com.spotifry.music.data.PeriodId.MONTH, "This month", 0, 0, 0, 0, 0, 0, List(24) { 0L }, List(24) { null }, emptyList(), emptyList()), false))
    }

    @Test fun talkBackCanStepThroughTheHours() {
        show(row(at(2027, 3, 10, 1), "a", 600), row(at(2027, 3, 10, 2), "b", 60))
        val node = rule.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.CustomActions))
        val actions = node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        rule.runOnIdle { actions.first { it.label == "Next hour" }.action() }
        rule.waitForIdle()
        rule.onNodeWithText("1 a.m.").assertExists()
        rule.runOnIdle { actions.first { it.label == "Next hour" }.action() }
        rule.waitForIdle()
        rule.onNodeWithText("2 a.m.").assertExists()
    }

    @Test fun emptyingTheLogGoesBack() = kotlinx.coroutines.runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>(), com.spotifry.music.data.db.AppDatabase::class.java).allowMainThreadQueries().build()
        db.listens().insert(com.spotifry.music.data.db.ListenEntity(now, "a", 60))
        var backs = 0
        val vm = ListeningViewModel(db.listens())
        rule.setContent { MusicTheme { ListeningScreen(vm, emptySet(), false, { backs++ }, { _, _, _ -> }, {}, {}, {}, 0.dp) } }
        rule.waitUntil(5_000) { vm.stats.value != null }
        rule.waitForIdle()
        assertEquals(0, backs)
        db.listens().clear()
        rule.waitUntil(5_000) { backs == 1 }
        db.close()
    }

    @Test fun homesRowWaitsForAListenAndSaysTheWeek() = kotlinx.coroutines.runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>(), com.spotifry.music.data.db.AppDatabase::class.java).allowMainThreadQueries().build()
        val vm = ListeningRowViewModel(db.listens())
        rule.setContent { MusicTheme { ListeningRow(vm, false, {}) } }
        rule.waitForIdle()
        missing("Your rotation")
        db.listens().insert(com.spotifry.music.data.db.ListenEntity(1_000, "a", 60)) // long ago
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Your rotation").fetchSemanticsNodes().isNotEmpty() }
        missing("1 min this week")
        db.listens().insert(com.spotifry.music.data.db.ListenEntity(System.currentTimeMillis(), "a", 120))
        rule.waitUntil(5_000) { rule.onAllNodesWithText("2 min this week").fetchSemanticsNodes().isNotEmpty() }
        db.close()
    }

    @Test @Config(fontScale = 2f) fun homesRowKeepsBothLinesAtBigText() = kotlinx.coroutines.runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>(), com.spotifry.music.data.db.AppDatabase::class.java).allowMainThreadQueries().build()
        db.listens().insert(com.spotifry.music.data.db.ListenEntity(System.currentTimeMillis(), "a", 120))
        val vm = ListeningRowViewModel(db.listens())
        rule.setContent { MusicTheme { ListeningRow(vm, false, {}) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("2 min this week").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Your rotation").assertIsDisplayed()
        rule.onNodeWithText("2 min this week").assertIsDisplayed()
        val w = rule.onNodeWithText("2 min this week").fetchSemanticsNode().boundsInRoot.right
        assert(w <= 360f * rule.density.density) { "second line runs past the screen: $w" }
        db.close()
    }

    @Test fun anHourIsFoundFromWhereTheTapFell() {
        assertEquals(0, hourAt(50f, 0f, 100f))
        assertEquals(6, hourAt(100f, 50f, 100f))
        assertEquals(12, hourAt(50f, 100f, 100f))
        assertEquals(18, hourAt(0f, 50f, 100f))
        assertEquals(0, hourAt(52f, 0f, 100f))
        assertNull(hourAt(50f, 50f, 100f))
    }
}
