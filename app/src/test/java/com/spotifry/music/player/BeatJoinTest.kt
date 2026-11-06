package com.spotifry.music.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Just enough of a player: a list of songs, a position the test moves, and what was asked of it. */
@UnstableApi
private class FakePlayer(private val items: List<MediaItem>, private val lengthMs: Long = 200_000) : SimpleBasePlayer(Looper.getMainLooper()) {
    var index = 0; var pos = 0L; var play = true; var vol = 1f; var repeat = Player.REPEAT_MODE_OFF
    var suppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE
    var play_state = Player.STATE_READY
    var released = false
    val seeks = ArrayList<kotlin.Pair<Int, Long>>()
    var params = PlaybackParameters.DEFAULT

    fun changed() = invalidateState()

    override fun getState(): State = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setPlaylist(items.map { MediaItemData.Builder(it.mediaId).setMediaItem(it).setDurationUs(lengthMs * 1000).build() })
        .setCurrentMediaItemIndex(index).setContentPositionMs(pos).setPlaybackState(play_state)
        .setPlayWhenReady(play, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaybackSuppressionReason(suppression)
        .setVolume(vol).setRepeatMode(repeat).setPlaybackParameters(params).build()

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> { vol = volume; return Futures.immediateVoidFuture() }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> { play = playWhenReady; return Futures.immediateVoidFuture() }
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> { repeat = repeatMode; return Futures.immediateVoidFuture() }
    override fun handleRelease(): ListenableFuture<*> { released = true; return Futures.immediateVoidFuture() }
    override fun handleSetPlaybackParameters(p: PlaybackParameters): ListenableFuture<*> { params = p; return Futures.immediateVoidFuture() }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        seeks += mediaItemIndex to positionMs
        index = if (mediaItemIndex == C.INDEX_UNSET) index else mediaItemIndex
        pos = positionMs
        return Futures.immediateVoidFuture()
    }
}

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BeatJoinTest {
    private val a = MediaItem.Builder().setMediaId("a").setUri("music://a").build()
    private val b = MediaItem.Builder().setMediaId("b").setUri("music://b").build()
    private val main = FakePlayer(listOf(a, b))
    private val tails = ArrayList<FakePlayer>()
    private var fade = 5_000L
    private var grid: BeatGrid? = BeatGrid(0.0, 500.0, .9f, 0, Long.MAX_VALUE / 4)
    private var beatsOn = true
    private var stopsAfter = false
    private var sleep = 1f

    private val join = BeatJoin(
        main,
        { item, at -> FakePlayer(listOf(item)).also { it.pos = at; it.play = false; it.vol = 0f; tails += it } },
        { _, _, _ -> grid },
        CoroutineScope(Dispatchers.Unconfined),
        { fade }, { beatsOn }, { stopsAfter }, { sleep },
    )
    private val tail get() = tails.last()

    private fun at(ms: Long) { main.pos = ms; main.changed() }

    /** Moves both voices on together until [until] says stop, ticking as the service does. */
    private fun run(ms: Long, gains: MutableList<Float?>? = null) {
        repeat((ms / 50).toInt()) {
            if (main.play) main.pos += (50 * main.params.speed).toLong()
            main.changed()
            if (tails.isNotEmpty() && tail.play && !tail.released) { tail.pos += (50 * tail.params.speed).toLong(); tail.changed() }
            val g = join.tick(true)
            gains?.add(g)
        }
    }

    /** Plays on to the moment the main player moves to the new song. */
    private fun toHandover() {
        at(160_000); join.tick(true)
        while (main.index == 0 && main.pos < 199_000) run(50)
    }

    @Test fun nothingHappensFarFromTheEnd() {
        at(10_000); run(1_000)
        assertTrue(tails.isEmpty()); assertEquals(0, main.index)
    }

    @Test fun theTailVoiceTakesOverSilentlyAndTheMainPlayerMovesOn() {
        toHandover()
        assertEquals(1, main.index)
        assertEquals(1, tails.size)
        // the first beat 2.5 s in is the cue, and it starts 2.5 s before that (less its start-up time) to be lined up unheard
        assertEquals(1 to 350L, main.seeks.first())
        assertEquals(1f, tail.vol, .01f)
    }

    @Test fun theVoicesCrossAndTheTailGoesAway() {
        toHandover()
        val gains = ArrayList<Float?>()
        run(12_000, gains)
        assertTrue(tail.released)
        val heard = gains.filterNotNull()
        assertTrue("main rises from silent", heard.first() < .1f && heard.last() > .9f)
        for (i in 1 until heard.size) assertTrue("no dips", heard[i] >= heard[i - 1] - .01f || heard[i - 1] == 0f)
        // after the join the service's own fade is back in charge
        assertNull(join.tick(true))
    }

    @Test fun midwayBothVoicesAreAboutEven() {
        toHandover()
        run(600)
        val cross = ArrayList<kotlin.Pair<Float, Float>>()
        repeat(120) { run(50, null); join.tick(true)?.let { cross += it to tail.vol } }
        val mid = cross.minBy { kotlin.math.abs(it.first - it.second) }
        assertEquals(.707f, mid.first, .05f); assertEquals(.707f, mid.second, .05f)
    }

    @Test fun aNewSongOutOfStepCatchesUpAndGoesBackToItsSpeed() {
        toHandover()
        // plan: old song 194 s meets new song 2.5 s. It comes up 250 ms late
        main.pos = tail.pos - 194_000 + 2_500 - 250; main.changed()
        run(800)
        assertTrue("runs fast, unheard: ${main.params.speed}", main.params.speed > 1f)
        run(3_000)
        val off = (main.pos - 2_500) - (tail.pos - 194_000)
        assertTrue("still off by $off ms", kotlin.math.abs(off) <= 40)
        assertEquals(1f, main.params.speed, .001f)
        assertEquals(1f, tail.params.speed, .001f)
    }

    @Test fun aSeekWhileCatchingUpPutsTheSpeedBack() {
        toHandover()
        main.pos = tail.pos - 194_000 + 2_500 - 250; main.changed()
        run(800)
        assertTrue(main.params.speed > 1f)
        main.seekTo(1, 60_000)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1f, main.params.speed, .001f)
    }

    @Test fun aTailVoiceOutOfStepCatchesUpSilently() {
        at(160_000); join.tick(true)
        while (tails.isEmpty()) run(50)
        // it starts 300 ms ahead for its start-up time; this leaves it 200 behind
        tail.pos -= 500; tail.changed()
        run(800)
        assertTrue("tail at ${tail.params.speed}", tail.params.speed > 1f)
        assertEquals(0f, tail.vol, .001f)
        run(2_500)
        assertTrue("off by ${tail.pos - main.pos}", kotlin.math.abs(tail.pos - main.pos) <= 40)
        assertEquals(1f, tail.params.speed, .001f)
    }

    @Test fun theOldSongEndingFirstLeavesTheNewOneFullyUp() {
        toHandover()
        main.play_state = Player.STATE_BUFFERING; main.changed()
        tail.play_state = Player.STATE_ENDED; tail.changed()
        assertNull(join.tick(true))
        assertTrue(tail.released)
    }

    @Test fun pauseAndACallHoldTheTailVoiceToo() {
        toHandover(); run(1_000)
        main.play = false; main.changed()
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(tail.play)
        main.play = true; main.changed()
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(tail.play)
        main.suppression = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS; main.changed()
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(tail.play)
    }

    @Test fun aStopSilencesTheTailVoiceToo() {
        toHandover(); run(1_000)
        main.play_state = Player.STATE_IDLE; main.changed()
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(tail.play)
    }

    @Test fun whilePausedTheCrossHolds() {
        toHandover(); run(3_000)
        val before = join.tick(true)
        main.play = false; main.changed()
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        repeat(10) { assertEquals(before!!, join.tick(true)!!, .001f) }
    }

    @Test fun aSeekDuringTheJoinLetsTheTailGo() {
        toHandover(); run(2_000)
        main.seekTo(1, 60_000)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(join.tick(true))
        repeat(4) { join.tick(true) }
        assertTrue(tail.released)
    }

    @Test fun nextDuringTheJoinLetsTheTailGo() {
        toHandover(); run(2_000)
        main.seekTo(0, 0) // Back to the old song
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        repeat(4) { join.tick(true) }
        assertTrue(tail.released)
        assertEquals(0, main.index)
    }

    @Test fun aSeekBeforeTheHandoverPutsItOff() {
        at(160_000); join.tick(true); run(30_000)
        main.seekTo(0, 20_000)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        run(1_000)
        assertEquals(0, main.index)
        assertNull(join.tick(true))
    }

    @Test fun repeatOneNeverJoins() {
        main.repeat = Player.REPEAT_MODE_ONE; main.changed()
        at(195_000); run(2_000)
        assertTrue(tails.isEmpty()); assertEquals(0, main.index)
    }

    @Test fun stopAfterThisSongNeverJoins() {
        stopsAfter = true
        toHandover()
        assertTrue(tails.isEmpty()); assertEquals(0, main.index)
    }

    @Test fun nextSongNotReadyLeavesEverythingAlone() {
        grid = null
        toHandover()
        assertTrue(tails.isEmpty()); assertEquals(0, main.index)
    }

    @Test fun unsureWithNoFadeLeavesTheVolumeAlone() {
        grid = BeatGrid(0.0, 500.0, .1f, 0, Long.MAX_VALUE / 4); fade = 0
        at(160_000)
        repeat(50) { main.pos += 800; assertNull(join.tick(true)) }
        assertTrue(tails.isEmpty())
    }

    @Test fun skippingSilenceGetsAPlainOverlap() {
        beatsOn = false
        toHandover()
        assertEquals(1, main.index)
        assertEquals("nothing to line up: from where the song starts", 0L, main.seeks.first().second)
    }

    @Test fun switchingOffMidwayFinishesCleanly() {
        toHandover(); run(2_000)
        assertNull(join.tick(false))
        repeat(4) { join.tick(false) }
        assertTrue(tail.released)
        assertTrue(join.idle)
    }

    @Test fun theServiceGoingAwayStopsEverything() {
        toHandover()
        join.release()
        assertTrue(tail.released)
    }

    @Test fun theSleepFadeReachesTheTailVoice() {
        toHandover(); run(600)
        sleep = .5f
        repeat(2) { run(50) }
        assertTrue("tail at ${tail.vol}", tail.vol <= .5f)
    }

    @Test fun aSeekAlreadyOverPlansAgainAfterASeekBack() {
        toHandover(); run(11_000)
        assertTrue(tail.released)
        main.seekTo(0, 150_000)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        run(100)
        assertEquals(0, main.index)
    }
}
