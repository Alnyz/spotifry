package com.spotifry.music.ui.firstlaunch

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.PickedArtist
import com.spotifry.music.data.Settings
import com.spotifry.music.data.SpotifyTrack
import com.spotifry.music.data.db.AppDatabase
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.ResolvedStream
import com.spotifry.music.data.source.Song
import com.spotifry.music.ui.playlists.ImportState
import com.spotifry.music.ui.playlists.ImportViewModel
import com.spotifry.music.ui.theme.MusicTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirstLaunchScreenTest {
    @get:Rule val compose = createComposeRule()

    private val starters = listOf("Tulus", "Raisa", "Hindia", "Queen", "Coldplay").map { Starter(it) }
    private fun picked(n: Int) = starters.take(n).map(Starter::picked)

    private fun artists(picked: List<PickedArtist>, offline: Boolean = false, onRetry: () -> Unit = {}, onContinue: () -> Unit = {}) = compose.setContent {
        MusicTheme { ArtistsStep(starters, picked, "", {}, ArtistSearch.Idle, offline, "1 of 2", "Skip", "Continue", {}, onRetry, {}, onContinue) }
    }

    @Test fun nothingPickedAsksForThree() {
        artists(emptyList())
        compose.onNodeWithText("Pick 3 or more").assertIsDisplayed()
        compose.onNodeWithText("Where these come from:", substring = true).assertDoesNotExist()
    }

    @Test fun twoPickedAsksForOneMoreAndDoesNothing() {
        var went = 0
        artists(picked(2), onContinue = { went++ })
        compose.onNodeWithText("Pick 1 more").performClick()
        assertEquals(0, went)
    }

    @Test fun enoughPickedContinuesAndCounts() {
        var went = 0
        artists(picked(5), onContinue = { went++ })
        compose.onNodeWithText("Continue · 5 picked").performClick()
        assertEquals(1, went)
    }

    @Test fun offlineSaysSoAndOffersTryAgain() {
        var retried = 0
        artists(emptyList(), offline = true, onRetry = { retried++ })
        compose.onNodeWithTag("starters").performScrollToNode(hasText("No connection.", substring = true))
        compose.onNodeWithText("No connection.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    private fun link(state: ImportState, offline: Boolean = false, onSkip: () -> Unit = {}, onImport: (String) -> Unit = {}) = compose.setContent {
        MusicTheme { LinkStep(state, offline, emptyMap(), onImport, onSkip, {}, {}) }
    }

    @Test fun notAPlaylistLinkShowsTheImportsOwnMessage() {
        link(ImportState.Input("That isn't a Spotify playlist link"))
        compose.onNodeWithText("That isn't a Spotify playlist link").assertIsDisplayed()
        // the message belongs to the link that caused it: typing clears it
        compose.onNode(hasSetTextAction()).performTextInput("https://open.spotify.com/playlist/x")
        compose.onAllNodesWithText("That isn't a Spotify playlist link").assertCountEquals(0)
    }

    @Test fun failedOnlineShowsOneMessageAndOfflineAddsTheHint() {
        link(ImportState.Failed("Couldn't open that playlist. Check it's public and you're online."))
        compose.onNodeWithText("Couldn't open that playlist. Check it's public and you're online.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test fun failedOfflineSaysNothingWasAdded() {
        link(ImportState.Failed("Couldn't open that playlist. Check it's public and you're online."), offline = true)
        compose.onNodeWithText("No connection.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Nothing was added.", substring = true).assertIsDisplayed()
    }

    @Test fun skipOnTheLinkScreenWorksWhileIdleAndIsOffWhileImporting() {
        var skipped = 0
        link(ImportState.Input(), onSkip = { skipped++ })
        compose.onNodeWithText("Skip").performClick()
        assertEquals(1, skipped)
    }

    @Test fun skipIsOffWhileTheImportRuns() {
        link(ImportState.Opening)
        compose.onNodeWithText("Skip").assertIsNotEnabled()
        compose.onNodeWithText("Cancel import").assertIsDisplayed()
    }

    @Test fun finishedOffersGoToHome() {
        link(ImportState.Done(1, "chill", 97, 100, listOf(SpotifyTrack("a", "b", 1), SpotifyTrack("c", "d", 2), SpotifyTrack("e", "f", 3))))
        compose.onNodeWithText("97 of 100 songs added").assertIsDisplayed()
        compose.onNodeWithText("3 songs were not found on YouTube Music.").assertIsDisplayed()
        compose.onNodeWithText("Go to Home").assertIsDisplayed()
    }

    @Test fun skipOnAFailedImportEndsTheFirstLaunch() {
        var skipped = 0
        compose.setContent { MusicTheme { LinkStep(ImportState.Failed("x"), false, emptyMap(), {}, { skipped++ }, {}, {}) } }
        compose.onAllNodesWithText("Skip")[0].performClick()
        assertEquals(1, skipped)
    }

    /** The whole screen with a real import that never answers: start it, cancel it, and the link box is back. */
    @Test fun cancellingTheImportBringsTheLinkBoxBack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val source = object : MusicSource {
            override suspend fun search(query: String) = emptyList<Song>()
            override suspend fun resolve(song: Song, low: Boolean): ResolvedStream = error("unused")
            override suspend fun related(song: Song) = emptyList<Song>()
        }
        val importVm = ImportViewModel({ awaitCancellation() }, source, db.playlists(), db.songs())
        val settings = Settings(context.getSharedPreferences("screen", 0))
        val vm = FirstLaunchViewModel(source, settings)
        compose.setContent { MusicTheme { FirstLaunchScreen(vm, importVm, emptyMap()) } }
        compose.onNodeWithText("Start fresh").performClick()
        compose.onNodeWithText("Skip").performClick()
        compose.onNodeWithText("2 of 3").assertIsDisplayed()
        compose.onNodeWithText("Skip").performClick()
        compose.onNodeWithText("3 of 3").assertIsDisplayed()
        compose.onNodeWithText("Bring a playlist").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("https://open.spotify.com/playlist/2wauuVc0v7DBWGylJ6nFFr")
        compose.onNodeWithText("Import").performClick()
        compose.onNodeWithText("Opening the playlist…").assertIsDisplayed()
        compose.onNodeWithText("Cancel import").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Bring a playlist").assertIsDisplayed()
        assertFalse(settings.firstLaunchSeen.value)
        compose.onNodeWithText("Skip").performClick()
        assertTrue(settings.firstLaunchSeen.value)
        db.close()
    }

    @Test fun welcomeOffersBothChoicesAndRestoreOpensThePicker() {
        var taps = 0; var fresh = 0
        compose.setContent { MusicTheme { WelcomeStep(emptyList(), { fresh++ }, { taps++ }) } }
        compose.onNodeWithText("Welcome to", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Restore a backup").performClick()
        compose.onNodeWithText("Start fresh").performClick()
        assertEquals(1, taps); assertEquals(1, fresh)
    }

    @Test fun nameContinueWaitsForSomethingTypedAndStopsAtTwenty() {
        var name by mutableStateOf(""); var went = 0
        compose.setContent { MusicTheme { NameStep(name, { name = it }, "1 of 3", { went++ }, {}) } }
        compose.onNodeWithText("1 of 3").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsNotEnabled().performClick()
        assertEquals(0, went)
        compose.onNodeWithTag("name field").performTextInput("Alexandria Kristofer Jones")
        assertEquals("Alexandria Kristofer", name)
        compose.onNodeWithText("20/20").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(1, went)
    }

    /** The page opens with the photo and question in view: no focus, no keyboard, until the field is tapped. */
    @Test fun nameOpensWithoutTheKeyboardAndATapOnTheFieldAsksForIt() {
        lateinit var view: View
        compose.setContent { view = LocalView.current; MusicTheme { NameStep("", {}, "1 of 3", {}, {}) } }
        compose.waitForIdle()
        val imm = shadowOf(view.context.getSystemService(InputMethodManager::class.java))
        compose.onNodeWithTag("name field").assertIsNotFocused()
        assertFalse(imm.isSoftInputVisible)
        compose.onNodeWithTag("name field").performClick()
        compose.onNodeWithTag("name field").assertIsFocused()
    }

    /**
     * The keyboard opening and closing moves the field once, to where it stays: the photo is worked out from the room on
     * screen in the same pass, never from a height measured a frame earlier (that flipped the page back and forth).
     */
    @Config(qualifiers = "w384dp-h848dp")
    @Test fun namePageSettlesAtOnceAsTheKeyboardOpensAndCloses() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            // as FirstLaunchScreen hosts it
            MusicTheme { Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) { NameStep("", {}, "1 of 3", {}, {}) } }
        }
        compose.mainClock.autoAdvance = false
        val density = view.resources.displayMetrics.density
        fun keyboard(dp: Int) {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, (dp * density).toInt()))
                .setVisible(WindowInsetsCompat.Type.ime(), dp > 0).build()
            compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view, insets) }
        }
        // where the button sits on each of the next frames
        fun frames() = List(8) { compose.mainClock.advanceTimeByFrame(); compose.onNodeWithText("Continue").fetchSemanticsNode().boundsInRoot }
        val closed = frames()
        repeat(2) {
            keyboard(380)
            val open = frames()
            assertEquals(1, open.distinct().size)
            // just above the keyboard, the foot's own margin under it
            assertEquals(848f - 380f - 20f, open.first().bottom, 1f)
            keyboard(0)
            val again = frames()
            assertEquals(1, again.distinct().size)
            assertEquals(closed.last(), again.first())
            assertEquals(848f - 20f, again.first().bottom, 1f)
        }
    }

    @Test fun inkComesUpWithEachLetterAndIsFullByTheThird() {
        assertEquals(0f, inkFor(""), 0f)
        assertEquals(0f, inkFor("   "), 0f)
        assertEquals(1 / 3f, inkFor("A"), 1e-6f)
        assertEquals(2 / 3f, inkFor("Al"), 1e-6f)
        assertEquals(1f, inkFor("Ali"), 0f)
        assertEquals(1f, inkFor("Maximiliana Wolfens"), 0f)
        // spaces are not letters; an accent typed as its own mark belongs to its letter
        assertEquals(2 / 3f, inkFor("A l"), 1e-6f)
        assertEquals(2 / 3f, inkFor("Zo\u0308"), 1e-6f)
    }

    @Test fun nameTakesTwoLinesWithTheKeyboardClosedAndOneWhileItIsOpen() {
        assertEquals(2, nameLines(keyboard = false))
        assertEquals(1, nameLines(keyboard = true))
    }

    @Test fun plainPhotoIsGreyAndFullInkRunsGreyToTheAccent() {
        val ink = Color(0xFFFF8A3D)
        val grey = sleeveMatrix(0f, ink).values
        for (c in 0 until 5) { assertEquals(grey[c], grey[5 + c], 0f); assertEquals(grey[c], grey[10 + c], 0f) }
        val full = sleeveMatrix(1f, ink).values
        assertEquals(ink.green / ink.red, full[6] / full[1], 1e-4f)
        assertEquals(ink.blue / ink.red, full[11] / full[1], 1e-4f)
    }

    @Test fun theNameIsOnThePhotoOnlyOnceSomethingIsTyped() {
        var name by mutableStateOf("")
        compose.setContent { MusicTheme { NameStep(name, { name = it }, "1 of 3", {}, {}) } }
        compose.onNodeWithTag("sleeve name").assertDoesNotExist()
        name = "   "
        compose.onNodeWithTag("sleeve name").assertDoesNotExist()
        name = "Ali"
        compose.onNodeWithTag("sleeve name").assertExists()
        name = ""
        compose.onNodeWithTag("sleeve name").assertDoesNotExist()
    }

    /** Accents and the longest names are set on the photo; TalkBack hears the name once, from the field. */
    @Test fun accentedAndLongNamesAreDrawnAndReadOnce() {
        var name by mutableStateOf("")
        compose.setContent { MusicTheme { NameStep(name, { name = it }, "1 of 3", {}, {}) } }
        for (n in listOf("José", "Zoë", "Łukasz", "Łukasz Zoë", "Maximiliana Wolfens", "Alexandriakristoferj")) {
            name = n
            compose.onNodeWithTag("sleeve name").assertExists()
            compose.onAllNodesWithText(n).assertCountEquals(1)
            compose.onNodeWithContentDescription("Name or nickname").assertExists()
        }
    }

    /** The size step-down: lines break only between words, at most the lines allowed, and a long single word stays whole. */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun nameFitsBreakingOnlyBetweenWords() {
        val fits = mutableMapOf<String, TextLayoutResult>()
        lateinit var density: Density
        compose.setContent {
            density = LocalDensity.current
            val m = rememberTextMeasurer()
            with(density) {
                val w = 354.dp.roundToPx()
                fits["two"] = fitName(m, "Maximiliana Wolfens", density, w, 500.dp.roundToPx(), 2)
                fits["one"] = fitName(m, "Maximiliana Wolfens", density, w, 200.dp.roundToPx(), 1)
                fits["accents"] = fitName(m, "Łukasz Zoë", density, w, 500.dp.roundToPx(), 2)
                fits["word"] = fitName(m, "Alexandriakristoferj", density, 200.dp.roundToPx(), 500.dp.roundToPx(), 2)
            }
        }
        compose.waitForIdle()
        for ((key, l) in fits) {
            val text = l.layoutInput.text.text
            assertTrue(key, l.lineCount <= (if (key == "one") 1 else 2))
            for (i in 0 until l.lineCount - 1) assertEquals(key, ' ', text[l.getLineEnd(i) - 1])
        }
        assertEquals(1, fits["one"]!!.lineCount)
        assertEquals(1, fits["word"]!!.lineCount)
        // with room for two lines the name is set bigger than on one
        assertTrue(fits["two"]!!.layoutInput.style.fontSize.value > fits["one"]!!.layoutInput.style.fontSize.value)
    }
}
