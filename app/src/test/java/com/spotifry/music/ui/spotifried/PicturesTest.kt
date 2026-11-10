package com.spotifry.music.ui.spotifried

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.spotifry.music.data.TopSong
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.spotifried.CardId
import com.spotifry.music.data.spotifried.MonthTop
import com.spotifry.music.data.spotifried.ObsessionDay
import com.spotifry.music.data.spotifried.ageStats
import com.spotifry.music.data.spotifried.stats
import com.spotifry.music.ui.components.loadCover
import com.spotifry.music.ui.share.CardBackground
import com.spotifry.music.ui.share.drawCard
import com.spotifry.music.ui.theme.AccentChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.ref.WeakReference
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PicturesTest {
    // an app screen on top, as in the app: the card is drawn under it
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get().apply {
        setContent { Box(Modifier.fillMaxSize().background(Color.White)) }
    }
    private val decor get() = activity.window.decorView as ViewGroup
    private val accent = AccentChoice.EMBER.accent!!
    private val red = Color(0xFFFF0000).toArgb()

    private fun state(url: String? = null, path: String? = null): SpotifriedState {
        val song = SongEntity("s", "Paper Lanterns", "Hollow Birds", 200, url, null, null, path)
        val s = stats(
            plays = 400, songs = 80, seconds = 612 * 3600L, daySeconds = List(365) { 3600L + it * 30L },
            hours = List(24) { if (it == 3) 9000L else 600L + it * 100L }, nightShare = .4f, replayedSongs = 12,
            topSong = TopSong(song, 4600, 23), obsession = ObsessionDay(song, LocalDate.of(2026, 3, 14), 8, List(8) { 20f + it }),
            months = List(12) { MonthTop(YearMonth.of(2026, it + 1), song, 30 - it) },
            age = ageStats(1998, 1975, 40, 40).copy(years = List(40) { 1975 + it }),
        )
        return SpotifriedState(s, CardId.entries, emptyList(), 40, 0)
    }

    private val screen = Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)

    /**
     * Runs [block] on the main thread the way the app does, letting Robolectric's clock and frames move until it ends.
     * Robolectric never draws the window by itself, so each frame draws it here, as the phone's display does (in software
     * here, on the GPU there).
     */
    private fun <T> onMain(block: suspend CoroutineScope.() -> T): T {
        val job = CoroutineScope(Dispatchers.Main).async(block = block)
        val until = System.currentTimeMillis() + 60_000
        while (!job.isCompleted) {
            check(System.currentTimeMillis() < until) { "capture hung" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            decor.draw(Canvas(screen))
            Thread.sleep(1)
        }
        return job.getCompleted()
    }

    private fun capture(id: CardId, s: SpotifriedState = state(), limitMs: Long = 10_000, cover: suspend (SongEntity) -> Unit = {}) =
        onMain { captureStoryCard(activity, id, s, accent, limitMs, cover) }

    private fun Bitmap.count(color: Int, x: IntRange = 0 until width, y: IntRange = 0 until height) =
        y.sumOf { j -> x.count { i -> getPixel(i, j) == color } }

    @Test fun eachCardIs1080By1920() {
        for (id in CardId.entries) {
            val b = capture(id)
            assertEquals("$id", 1080 to 1920, b.width to b.height)
            assertEquals(Bitmap.Config.ARGB_8888, b.config)
            b.recycle()
        }
    }

    @Test fun cardIsNotOneFlatColour() {
        for (id in CardId.entries) {
            val b = capture(id)
            val colours = (0 until 1920 step 7).flatMap { y -> (0 until 1080 step 7).map { x -> b.getPixel(x, y) } }.toSet()
            assertTrue("$id has ${colours.size} colours", colours.size > 50)
            b.recycle()
        }
    }

    @Test fun watermarkIsDrawnTopLeft() {
        val b = capture(CardId.HOURS)
        val bg = b.getPixel(1079, 100) // top right: the story's background, nothing drawn there
        val marked = b.count(bg, 80 until 400, 90 until 145)
        assertTrue("watermark pixels: ${320 * 55 - marked}", 320 * 55 - marked > 1000)
        // and it stays in the band above the card
        assertEquals(320 * 30, b.count(bg, 80 until 400, 145 until 175))
        b.recycle()
    }

    @Test fun drawCardStillMatchesBeforeRefactor() {
        val b = drawCard(ApplicationProvider.getApplicationContext(), "Paper Lanterns", "Hollow Birds", null, listOf(null), false, true, CardBackground.BLACK, null, Color(0xFFFF7A3D))
        // the hash recorded from drawCard before its watermark was moved into drawWatermark
        val band = IntArray(1080 * 160).also { b.getPixels(it, 0, 1080, 0, 0, 1080, 160) }
        assertEquals(-609345572, band.contentHashCode())
    }

    @Test fun topSongWaitsForTheCover() {
        val file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "late.png").apply { delete() }
        val url = "https://example.invalid/cover.jpg"
        // the file appears only after 3 s, so a picture taken any earlier could not show it
        val late = capture(CardId.TOP_SONG, state(url, file.path)) {
            delay(3_000)
            Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(red) }.let { b -> file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            loadCover(null, it.thumbnailUrl, it.thumbPath, null)
        }
        assertTrue(late.count(red) > 10_000)
        late.recycle()
        // one that never arrives: the placeholder once the limit has passed, not a hang
        val start = SystemClock.uptimeMillis()
        val never = capture(CardId.TOP_SONG, state(url, file.path + ".none"), limitMs = 2_000) { awaitCancellation() }
        assertTrue(SystemClock.uptimeMillis() - start >= 2_000)
        assertEquals(0, never.count(red))
        assertTrue(never.count(Color(0xFF0B0B0C).toArgb()) < 1080 * 1920) // still a picture of the card
        never.recycle()
    }

    @Test fun releasesBetweenCards() {
        val views = decor.childCount
        // not recycled here: Robolectric keeps a recycled bitmap reachable, which would hide what this checks
        val first = WeakReference(capture(CardId.INTRO))
        val second = WeakReference(capture(CardId.HOURS))
        assertEquals(views, decor.childCount)
        repeat(5) { System.gc(); Thread.sleep(20) }
        assertNull(first.get())
        assertNull(second.get())
    }
}
