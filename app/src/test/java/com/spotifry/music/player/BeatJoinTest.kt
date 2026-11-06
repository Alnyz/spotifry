package com.spotifry.music.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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
private class FakePlayer(items: List<MediaItem>, private val lengthMs: Long = 200_000) : SimpleBasePlayer(Looper.getMainLooper()) {
    val items = items.toMutableList()
    var error: PlaybackException? = null
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
        .setVolume(vol).setRepeatMode(repeat).setPlaybackParameters(params).setPlayerError(error).build()

    fun drop(i: Int) { items.removeAt(i); changed() }

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
    private var oldGrid: BeatGrid? = null
    private var beatsOn = true
    private var stopsAfter = false
    private var sleep = 1f
    private var setting = PlaybackParameters.DEFAULT
    private var wakes = 0
    /** A wake that runs the loop before it returns, the worst a dispatcher could do. */
    private var tickInsideWake = false

    private val join = BeatJoin(
        main,
        { item, at -> FakePlayer(listOf(item)).also { it.pos = at; it.play = false; it.vol = 0f; tails += it } },
        { item, _, _ -> if (item.mediaId == "a") oldGrid ?: grid else grid },
        CoroutineScope(Dispatchers.Unconfined),
        { fade }, { beatsOn }, { stopsAfter }, { sleep }, { setting }, { wakes++; if (tickInsideWake) tickNow() },
    )
    private val tail get() = tails.last()
    private fun tickNow() { join.tick(true) }

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
        // .707 each for the same loudness, less the dip that keeps two loud songs under full level
        assertEquals(.566f, mid.first, .05f); assertEquals(.566f, mid.second, .05f)
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

    @Test fun aSeekBackAfterAJoinPlansItAgain() {
        toHandover(); run(11_000)
        assertTrue(tail.released)
        assertEquals(1, tails.size)
        main.seekTo(0, 160_000)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, main.index)
        while (main.index == 0 && main.pos < 199_000) run(50)
        assertEquals("a second tail voice for the second join", 2, tails.size)
        assertEquals(1, main.index)
    }

    private fun idle() = org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()

    @Test fun aTailStuckBufferingBringsTheMainPlayerBack() {
        toHandover(); run(50)
        tail.play_state = Player.STATE_BUFFERING; tail.changed(); idle()
        val seeks = main.seeks.size
        val gains = ArrayList<Float?>()
        repeat(40) { main.pos += 50; main.changed(); gains += join.tick(true) }
        assertTrue("released within about a second", tail.released)
        assertNull(gains.last())
        assertEquals("no seeks chasing a position that isn't moving", seeks, main.seeks.size)
    }

    @Test fun aTailFailingAfterTheHandoverBringsTheMainPlayerBack() {
        toHandover(); run(50)
        tail.error = PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        tail.play_state = Player.STATE_IDLE; tail.changed(); idle()
        assertNull(join.tick(true))
        assertTrue(tail.released)
    }

    private fun mainFails() {
        main.error = PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        main.play_state = Player.STATE_IDLE; main.changed(); idle()
    }

    @Test fun theNewSongFailingLetsTheOldSongPlayOut() {
        toHandover(); run(3_000)
        mainFails()
        assertTrue("old song goes on", tail.play)
        join.tick(true)
        assertEquals(1f, tail.vol, .01f)
        assertFalse(tail.released)
        tail.play_state = Player.STATE_ENDED; tail.changed(); idle()
        join.tick(true)
        assertTrue(tail.released)
    }

    @Test fun theNewSongFailingBeforeTheOverlapLetsTheOldSongPlayOut() {
        toHandover(); run(50)
        mainFails()
        assertTrue(tail.play)
        tail.play_state = Player.STATE_ENDED; tail.changed(); idle()
        join.tick(true)
        assertTrue(tail.released)
    }

    @Test fun twiceTheSpeedStillJoinsAndCatchesUpRight() {
        setting = PlaybackParameters(2f); main.params = setting; main.changed()
        toHandover()
        assertEquals(1, main.index)
        assertEquals(2f, tail.params.speed, .001f)
        // the first beat at or past 5 s of lead, at twice the speed
        val cue = main.seeks.first().second - 350 + 5_000
        main.pos = tail.pos - 194_000 + cue - 240; main.changed()
        // measuring takes 9 ticks, and catching up 240 ms at a quarter over twice the speed takes 9 more
        run(1_100)
        assertEquals("back at its speed by now", 2f, main.params.speed, .001f)
        val off = (main.pos - cue) - (tail.pos - 194_000)
        assertTrue("off by $off at twice the speed", kotlin.math.abs(off) <= 60)
    }

    @Test fun aSpeedChosenWhileCatchingUpIsTheOneItGoesBackTo() {
        toHandover()
        main.pos = tail.pos - 194_000 + 2_500 - 250; main.changed()
        run(800)
        assertTrue(main.params.speed > 1f)
        setting = PlaybackParameters(1.5f)
        run(3_000)
        assertEquals(1.5f, main.params.speed, .001f)
        assertEquals(1.5f, tail.params.speed, .001f)
    }

    @Test fun catchingUpStopsEarlyInTheOverlap() {
        toHandover()
        // kept 500 ms late up to the overlap, so it's still catching up as the overlap starts
        while (tail.pos < 194_000) { run(50); main.pos = tail.pos - 194_000 + 2_500 - 500; main.changed() }
        val seen = ArrayList<Pair<Float, Float>>()
        repeat(100) {
            main.pos += (50 * main.params.speed).toLong(); main.changed()
            tail.pos += 50; tail.changed()
            join.tick(true)?.let { g -> seen += g to main.params.speed }
        }
        assertTrue("it did catch up in the overlap", seen.any { it.second != 1f })
        val loudest = seen.filter { it.second != 1f }.maxOf { it.first }
        assertTrue("still off its speed at gain $loudest", loudest < .2f)
    }

    @Test fun theHandoverCrossAddsUpToOne() {
        at(160_000); join.tick(true)
        val sums = ArrayList<Float>()
        while (main.index == 0 && main.pos < 199_000) {
            main.pos += 50; main.changed()
            if (tails.isNotEmpty() && tail.play) { tail.pos += (50 * tail.params.speed).toLong(); tail.changed() }
            val g = join.tick(true)
            if (tails.isNotEmpty() && tail.vol > 0f) sums += tail.vol + (g ?: 1f)
        }
        assertTrue("the cross was seen", sums.isNotEmpty())
        assertTrue("same music adds up: ${sums.max()}", sums.max() <= 1.01f)
    }

    @Test fun removingTheNextSongBeforeTheHandoverCallsItOff() {
        at(160_000); join.tick(true)
        while (tails.isEmpty()) run(50)
        main.drop(1); idle()
        repeat(6) { run(50) }
        assertTrue(tail.released)
        assertEquals(0, main.index)
        assertTrue(main.seeks.isEmpty())
    }

    @Test fun repeatAllWrapsToTheFirstSong() {
        main.index = 1; main.repeat = Player.REPEAT_MODE_ALL; main.changed(); idle()
        at(160_000); join.tick(true)
        while (main.index == 1 && main.pos < 199_000) run(50)
        assertEquals(0, main.index)
        assertEquals(1, tails.size)
    }

    @Test fun aReadCutShortIsNotTakenForTheEndOfTheSong() {
        // read as far as 178 s, 22 s short of the real end
        oldGrid = BeatGrid(0.0, 500.0, .9f, 0, 178_000, whole = false)
        toHandover()
        assertEquals(1, main.index)
        assertTrue("old song at ${tail.pos}", tail.pos > 190_000)
    }

    @Test fun aSongFarFromItsEndKeepsItsFadeIn() {
        at(160_000); join.tick(true)
        run(100)
        assertNull("plan made, no override yet", join.tick(true))
    }

    @Test fun nothingNeedsQuickTicksFarFromTheEnd() {
        at(10_000); join.tick(true)
        assertFalse(join.quick)
        at(160_000); join.tick(true)
        assertFalse("waiting for its moment", join.quick)
        while (tails.isEmpty()) run(50)
        assertTrue(join.quick)
    }
    /** Ticks with only the tail advancing, as when the main player is held up. */
    private fun tailRunsOn(ticks: Int, gains: MutableList<Float?>? = null) = repeat(ticks) {
        tail.pos += 50; tail.changed(); main.changed()
        val g = join.tick(true)
        gains?.add(g)
    }

    private fun toPriming() { at(160_000); join.tick(true); while (tails.isEmpty()) run(50) }

    /** Plays on until the handover's first step, the one that halves both voices. */
    private fun toSwapping() {
        toPriming()
        var g: Float?
        do { run(50); g = join.tick(true) } while (g != .5f && main.index == 0)
        assertEquals(.5f, g)
    }

    @Test fun aNewSongUpLateFadesFromWhereItIsWithoutAJump() {
        toHandover()
        // the tail is far past where the overlap should have begun by the time the new song is up
        tail.pos += 6_000; tail.changed()
        val mainGain = ArrayList<Float>(); val tailGain = ArrayList<Float>()
        repeat(120) { run(50); join.tick(true)?.let { mainGain += it; tailGain += tail.vol } }
        assertTrue(mainGain.isNotEmpty())
        for (i in 1 until mainGain.size) {
            assertTrue("main stepped ${mainGain[i - 1]} to ${mainGain[i]}", kotlin.math.abs(mainGain[i] - mainGain[i - 1]) < .12f)
            assertTrue("tail stepped ${tailGain[i - 1]} to ${tailGain[i]}", kotlin.math.abs(tailGain[i] - tailGain[i - 1]) < .12f)
        }
        assertTrue("main starts low: ${mainGain.first()}", mainGain.first() < .1f)
        assertTrue("the fade still completes", tail.released)
    }

    @Test fun aMainPlayerHeldUpAfterBeingInStepPutsTheTailBackInStep() {
        toPriming(); run(3_500)
        val seeks = tail.seeks.size
        // main buffers for over a second while the silent tail runs on
        main.play_state = Player.STATE_BUFFERING; main.changed()
        tailRunsOn(30)
        main.play_state = Player.STATE_READY; main.changed()
        run(400)
        assertEquals("not handed over with the tail ahead", 0, main.index)
        assertTrue("tail seeked back", tail.seeks.size > seeks)
        assertTrue("tail near main again, off by ${tail.pos - main.pos}", kotlin.math.abs(tail.pos - main.pos) < 600)
    }

    @Test fun theMainPlayerFailingBeforeTheHandoverLetsTheTailGo() {
        toPriming(); run(100)
        mainFails()
        repeat(5) { join.tick(true) }
        assertTrue(tail.released)
        assertTrue(join.idle)
    }

    @Test fun theTailEndingDuringTheHandoverBringsTheMainPlayerBack() {
        toSwapping()
        tail.play_state = Player.STATE_ENDED; tail.changed(); idle()
        assertNull(join.tick(true))
        assertTrue(tail.released)
    }

    @Test fun theTailFailingDuringTheHandoverBringsTheMainPlayerBack() {
        toSwapping()
        tail.error = PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        tail.play_state = Player.STATE_IDLE; tail.changed(); idle()
        assertNull(join.tick(true))
        assertTrue(tail.released)
    }

    @Test fun aTailStallingDuringTheHandoverBringsTheMainPlayerBack() {
        toSwapping()
        tail.play_state = Player.STATE_BUFFERING; tail.changed(); idle()
        val gains = ArrayList<Float?>()
        repeat(30) { main.pos += 50; main.changed(); gains += join.tick(true) }
        assertTrue(tail.released)
        assertNull(gains.last())
        assertEquals("held until the guard gave up", .5f, gains.first()!!, .01f)
    }

    @Test fun theNewSongFailingMidOverlapBringsTheOldSongBackUpGently() {
        toHandover()
        val gains = ArrayList<Float?>()
        while (tail.vol > .4f || tail.vol == 0f) run(50)
        mainFails()
        val seen = arrayListOf(tail.vol)
        repeat(20) { join.tick(true); seen += tail.vol }
        for (i in 1 until seen.size) assertTrue("tail stepped ${seen[i - 1]} to ${seen[i]}", seen[i] >= seen[i - 1] && seen[i] - seen[i - 1] <= .15f)
        assertEquals(1f, seen.last(), .001f)
        assertFalse(tail.released)
    }

    @Test fun pausedMeansSlowTicksUnlessATailIsLeaving() {
        toPriming()
        assertTrue(join.quick)
        main.play = false; main.changed(); idle()
        assertFalse("nothing moves while paused", join.quick)
        main.play = true; main.changed(); idle()
        assertTrue(join.quick)
        main.play = false; main.changed(); idle()
        main.seekTo(0, 5_000); idle()
        join.tick(true)
        assertTrue("the leaving tail still fades out at full rate", join.quick)
        repeat(4) { join.tick(true) }
        assertFalse(join.quick)
    }

    /** Plays to just before the handover at 191.5 s, in step. */
    private fun toJustBeforeHandover() { toPriming(); run(5_900); assertEquals(0, main.index) }

    @Test fun aMainPlayerBufferingAtTheHandoverPutsItOff() {
        toJustBeforeHandover(); run(50)
        main.play_state = Player.STATE_BUFFERING; main.changed()
        val gains = ArrayList<Float?>()
        tailRunsOn(8, gains)
        assertEquals("no handover while it isn't playing", 0, main.index)
        assertEquals(0f, tail.vol, .001f)
        assertTrue(gains.none { it == .5f })
    }

    @Test fun afterAHoldItOnlyHandsOverOnceMeasuredInStep() {
        toJustBeforeHandover()
        main.play_state = Player.STATE_BUFFERING; main.changed()
        tailRunsOn(1)
        main.play_state = Player.STATE_READY; main.changed()
        run(300)
        assertEquals("a fresh average takes longer than this", 0, main.index)
        run(800)
        assertEquals(1, main.index)
    }

    @Test fun aStallJustBeforeTheHandoverIsMeasuredAgainNotHandedOverWithAnEcho() {
        toJustBeforeHandover()
        main.play_state = Player.STATE_BUFFERING; main.changed()
        tailRunsOn(6)
        main.play_state = Player.STATE_READY; main.changed()
        // main crosses the handover well before a fresh average of the 300 ms the tail ran on
        run(300)
        assertEquals(0, main.index)
        assertEquals("both voices still one", 0f, tail.vol, .001f)
        run(2_000)
        assertEquals("too far to fix in the time left, so no join", 0, main.index)
        assertTrue(tail.released)
    }

    @Test fun aShortStallJustBeforeTheHandoverStillJoinsInStep() {
        toJustBeforeHandover()
        main.play_state = Player.STATE_BUFFERING; main.changed()
        tailRunsOn(2)
        main.play_state = Player.STATE_READY; main.changed()
        while (main.index == 0 && main.pos < 193_000) { val before = tail.pos - main.pos; run(50); if (main.index == 1) assertTrue("handed over off by $before", kotlin.math.abs(before) <= 40) }
        assertEquals(1, main.index)
    }

    @Test fun aSmallDriftIsLinedUpAgainBeforeTheHandover() {
        toPriming(); run(2_500)
        val seeks = tail.seeks.size
        tail.pos += 300; tail.changed()
        // a reading in progress, a clean one, the wait to settle and one more
        run(1_500)
        assertTrue("slowed, unheard: ${tail.params.speed}", tail.params.speed < 1f)
        assertEquals(0f, tail.vol, .001f)
        run(1_500)
        assertEquals(1f, tail.params.speed, .001f)
        assertTrue("off by ${tail.pos - main.pos}", kotlin.math.abs(tail.pos - main.pos) <= 40)
        assertEquals("no seek for so little", seeks, tail.seeks.size)
        assertEquals("not yet at the handover", 0, main.index)
    }

    @Test fun positionsMovingInStepsDontSetItHunting() {
        toPriming(); run(2_500)
        val seeks = tail.seeks.size
        // the reading swings a quarter second, as a position read on an emulator does
        tail.pos -= 180
        var up = true
        repeat(8) { tail.pos += if (up) 360 else -360; up = !up; tail.changed(); run(250); assertEquals(1f, tail.params.speed, .001f) }
        assertEquals(seeks, tail.seeks.size)
    }

    @Test fun aDriftTooBigToFixInTimeCallsTheJoinOffRatherThanEcho() {
        toPriming(); run(5_000)
        tail.pos += 500; tail.changed()
        run(3_000)
        assertEquals("the song runs on", 0, main.index)
        assertTrue(tail.released)
        assertTrue(main.seeks.isEmpty())
    }

    @Test fun pausingResumingAndLettingGoAskForATickNow() {
        toHandover(); run(1_000); wakes = 0
        main.play = false; main.changed(); idle()
        assertEquals(1, wakes)
        main.seekTo(1, 60_000); idle()
        assertEquals("the song picked while paused must not stay silent", 2, wakes)
        main.play = true; main.changed(); idle()
        assertEquals(3, wakes)
    }

    @Test fun switchedOffItDoesntAskForTicksOverAndOver() {
        toHandover(); run(1_000); wakes = 0
        repeat(5) { join.tick(false) }
        assertEquals("once, as it lets go, or the tail's fade out would race", 1, wakes)
    }

    @Test fun theOldSongCountsAsHeardOnlyOnceItsTailVoiceHasTakenOver() {
        toPriming()
        // running silently under the song on main, which is what's heard
        assertEquals(false, join.tailHeard)
        assertEquals(null, join.tailSong)
    }

    @Test fun throughTheOverlapTheOldSongIsTheOneHeard() {
        toMidOverlap()
        assertEquals(true, join.tailHeard)
        assertEquals(a.mediaId, join.tailSong)
        assertEquals(b.mediaId, main.currentMediaItem?.mediaId)
    }

    @Test fun pausedMidOverlapNothingIsHeard() {
        toMidOverlap()
        main.playWhenReady = false
        idle()
        join.tick(true)
        idle()
        assertEquals(false, join.tailHeard)
    }

    @Test fun theHandoverIsMarkedAsTheJoinsOwnMove() {
        val seen = ArrayList<Boolean>()
        main.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { seen += join.handingOver }
        })
        toHandover()
        idle()
        assertEquals(listOf(true), seen)
        assertEquals(false, join.handingOver)
    }

    private fun toMidOverlap(): Float {
        toHandover()
        while (tail.vol > .35f || tail.vol == 0f) run(50)
        return tail.vol
    }

    @Test fun aSeekMidOverlapOnlyEverTakesTheOldSongDown() {
        tickInsideWake = true
        val before = toMidOverlap()
        val seen = arrayListOf(before)
        // back to the start of the new song, the case where the overlap would read as not begun
        main.seekTo(1, 0); idle()
        seen += tail.vol
        repeat(4) { join.tick(true); seen += tail.vol }
        for (i in 1 until seen.size) assertTrue("rose ${seen[i - 1]} to ${seen[i]}", seen[i] <= seen[i - 1])
        assertTrue(tail.released)
    }

    @Test fun aSeekPastTheOverlapStillFadesTheOldSongOut() {
        tickInsideWake = true
        toMidOverlap()
        main.seekTo(1, 180_000); idle()
        assertFalse("not cut off", tail.released)
        repeat(4) { join.tick(true) }
        assertTrue(tail.released)
    }

    @Test fun nextMidOverlapOnlyEverTakesTheOldSongDown() {
        tickInsideWake = true
        main.items += MediaItem.Builder().setMediaId("c").setUri("music://c").build(); main.changed()
        val before = toMidOverlap()
        main.seekTo(2, 0); idle()
        assertTrue("rose $before to ${tail.vol}", tail.vol <= before)
        repeat(4) { join.tick(true) }
        assertTrue(tail.released)
    }

    @Test fun aStutterWhileMeasuringStartsTheMeasureAgain() {
        // close enough to the handover that the slower drift check can't save it
        toPriming(); run(5_500)
        // stops once, so it's measuring: three ticks to settle and five of the six readings
        main.play_state = Player.STATE_BUFFERING; main.changed(); tailRunsOn(1); tail.pos -= 50
        main.play_state = Player.STATE_READY; main.changed(); run(350)
        // 200 ms lost right at the end: one such reading in six would average to about 33 ms and pass
        main.play_state = Player.STATE_BUFFERING; main.changed(); tailRunsOn(4)
        main.play_state = Player.STATE_READY; main.changed()
        while (main.index == 0 && main.pos < 193_000) {
            val before = tail.pos - main.pos
            run(50)
            if (main.index == 1) assertTrue("handed over off by $before", kotlin.math.abs(before) <= 40)
        }
        run(200)
        assertTrue("joined in step or called off", main.index == 1 || tail.released)
    }

    @Test fun anUnevenSwingInThePositionDoesntSetItHuntingEither() {
        toPriming(); run(2_500)
        val seeks = tail.seeks.size
        // 0 then 250 ms ahead: six ticks can read 208 ms, while the middle of it is 125
        var up = true
        repeat(10) { tail.pos += if (up) 250 else -250; up = !up; tail.changed(); run(250); assertEquals(1f, tail.params.speed, .001f) }
        assertEquals(seeks, tail.seeks.size)
    }
}
