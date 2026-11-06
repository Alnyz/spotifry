package com.spotifry.music.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val TAG = "BeatJoin"
/** Analysis starts this long before the end: the player has buffered that far by then, so nothing is fetched twice. */
private const val LOOK_BEFORE_END_MS = 45_000L
private const val STRETCH_MS = 30_000L
private const val MIN_LEFT_MS = 6_000L
/** Time for the new song to start and be lined up, unheard, before the overlap: the first of it passes under the fade. */
const val JOIN_LEAD_MS = 2_500L
/** The tail voice starts this long earlier, silently, so it's running and in step when it takes over. */
private const val PRIME_MS = 6_000L
private const val SLACK_MS = 1_500L
/** Ticks (50 ms) a player must have been running for its position to be worth reading. */
private const val SETTLE_TICKS = 3
/** Ticks one reading is averaged over: a position can move in steps of a quarter second, so a single one says little. */
private const val AVERAGE_TICKS = 6
private const val IN_STEP_MS = 40L
private const val LOCKED_MS = 30L
private const val CATCH_UP = .25f
/** Further off than this it's quicker to seek than to catch up, though seeks land a varying time late. */
private const val SEEK_OVER_MS = 600L
/** An averaged drift past this while priming gets lined up again: at the handover it would be heard as an echo and a jump. */
private const val RELINE_MS = 150L
/** Ticks that drift is averaged over: half a second, a whole number of the quarter-second steps a position can move in. */
private const val DRIFT_TICKS = 10
private const val SEEK_START_UP_MS = 350L
private const val LEAVING_TICKS = 3
private const val TICK_MS = 50
/** The old song stuck for this long after the handover: the main player comes back up rather than stay silent. */
private const val STALL_TICKS = 20
/** How far into the overlap the new song may still run off its speed: it's about 16 dB down until here. */
private const val CATCH_UP_UNTIL = .1f
/** Both voices dip this much at the middle of the overlap, as two loud songs together can pass full level. */
private const val OVERLAP_CUT = .2f
/** A new song up later than this after its overlap should have begun fades from where it is, not partway in. */
private const val LATE_MS = 2 * TICK_MS.toLong()
/** Ticks the old song takes to come back up to full if the new song fails mid-overlap. */
private const val RAMP_TICKS = 10

/**
 * Overlaps one song into the next. [main] stays the one player the session, notification, queue and position follow: at
 * the join it moves on to the next song, with its volume at 0, and a second silent player, the tail voice, has already
 * taken over the rest of the old song. It has no audio focus and no session, and copies [main]'s play and pause. Any
 * user move during the join (Next, Back, a seek, a removal) just lets the tail voice go, so [main] behaves as always.
 * Anything unsure ends here without touching [main], and the song runs on as if this weren't on.
 *
 * Called with [tick] 20 times a second while playing; it returns the volume [main] should have, null to leave that alone.
 */
class BeatJoin(
    private val main: Player,
    private val newTail: (MediaItem, Long) -> Player,
    /** The beats of the stretch [fromMs] to [toMs] of a whole song, null if it can't be read. */
    private val look: suspend (MediaItem, Long, Long) -> BeatGrid?,
    private val scope: CoroutineScope,
    private val fadeMs: () -> Long,
    private val beatsAllowed: () -> Boolean,
    private val stopsAfterSong: () -> Boolean,
    private val sleepGain: () -> Float,
    /** The speed and pitch the user set, which [main] goes back to after catching up. */
    private val wanted: () -> PlaybackParameters,
    /** Asks for a [tick] now: while paused they are slow, and [main] may still be held silent from before the pause. */
    private val wake: () -> Unit = {},
) : Player.Listener {
    private class Pair(val out: MediaItem, val nextIndex: Int, val next: MediaItem) {
        val key = "${out.mediaId} ${out.trim} ${next.mediaId} ${next.trim}"
    }

    /**
     * Closes the gap between two players by running [p] a little fast or slow for just long enough: its sound isn't heard
     * yet and its pitch doesn't change. It measures, corrects, then measures again, so the steps in the position can't
     * set it hunting.
     */
    private class Liner {
        private val readings = ArrayList<Long>()
        private var slewing = 0
        var last = 0L; private set

        fun step(p: Player, off: Long, within: Long, base: () -> PlaybackParameters): Boolean {
            if (slewing > 0) {
                if (--slewing == 0) p.playbackParameters = base()
                return false
            }
            readings += off
            if (readings.size < AVERAGE_TICKS) return false
            last = readings.average().toLong()
            readings.clear()
            if (abs(last) <= within) return true
            val b = base()
            slewing = (abs(last) / CATCH_UP / b.speed / TICK_MS).toInt().coerceAtLeast(1)
            p.playbackParameters = PlaybackParameters(b.speed * (if (last < 0) 1 + CATCH_UP else 1 - CATCH_UP), b.pitch)
            return false
        }

        /** Fed while in step: whether the average of these readings has moved past [limit]. */
        fun drifted(off: Long, limit: Long): Boolean {
            readings += off
            if (readings.size < DRIFT_TICKS) return false
            last = readings.average().toLong()
            readings.clear()
            return abs(last) > limit
        }

        fun forget() = readings.clear()
    }

    private sealed interface Stage
    private data object Watching : Stage
    private class Done(val key: String) : Stage
    private class Looking(val key: String) : Stage { var job: Job? = null }
    private class Waiting(val key: String, val plan: JoinPlan) : Stage
    private class Priming(val key: String, val plan: JoinPlan, val tail: Player) : Stage { var ran = 0; var inStep = false; val line = Liner() }
    private class Swapping(val plan: JoinPlan, val tail: Player) : Stage { var step = 0; var stalled = 0 }
    private class Settling(val plan: JoinPlan, val tail: Player) : Stage { var ran = 0; var locked = false; var stalled = 0; val line = Liner() }
    private class Fading(val plan: JoinPlan, val tail: Player, val cue: Long, val lengthMs: Long, var locked: Boolean, val line: Liner) : Stage

    private var stage: Stage = Watching
    private val leaving = ArrayDeque<kotlin.Pair<Player, IntArray>>()
    private var ownSeek = false
    /** [main] is running a little off [wanted] to line up the beats. */
    private var mainLining = false
    private val known = HashMap<String, BeatGrid>()

    init { main.addListener(this) }

    /** Nothing under way, so this can be let go when the setting goes off. */
    val idle get() = leaving.isEmpty() && (stage === Watching || stage is Done)

    /** Something here needs a tick every 50 ms; otherwise a slow poll does, so the setting costs little for most of a song. */
    val quick: Boolean
        get() = leaving.isNotEmpty() || when (val s = stage) {
            // paused, nothing moves, and the tail follows the pause by listener: a slow poll will do
            is Priming, is Swapping, is Settling, is Fading -> main.playWhenReady
            is Waiting -> main.currentPosition >= primeAt(s.plan, wanted().speed) - 1_000
            else -> false
        }

    private fun primeAt(plan: JoinPlan, speed: Float) = plan.startMs - plan.leadMs - (PRIME_MS * speed).toLong()

    /** The plain fade doesn't start on a song about to be handed over; its fade-in, far from the end, is left alone. */
    private fun holdFade(pos: Long): Float? = if (main.duration - pos < fadeMs()) 1f else null

    private fun pairNow(): Pair? {
        val out = main.currentMediaItem ?: return null
        val i = main.nextMediaItemIndex
        if (main.repeatMode == Player.REPEAT_MODE_ONE || i == C.INDEX_UNSET || stopsAfterSong()) return null
        val next = main.getMediaItemAt(i)
        return if (next.mediaId == out.mediaId) null else Pair(out, i, next)
    }

    fun tick(on: Boolean): Float? {
        tickLeaving()
        if (!on) { letGo("switched off"); return null }
        val speed = wanted().speed
        val pos = main.currentPosition
        when (val s = stage) {
            Watching -> {
                val left = main.duration - pos
                if (main.duration == C.TIME_UNSET || !main.isPlaying || left > LOOK_BEFORE_END_MS * speed) return null
                val p = pairNow() ?: return null
                if (left < MIN_LEFT_MS * speed) { stage = Done(p.key); return null }
                start(p, (JOIN_LEAD_MS * speed).toLong())
            }
            is Done -> if (pairNow()?.key != s.key) stage = Watching
            is Looking -> if (pairNow()?.key != s.key) { s.job?.cancel(); stage = Watching }
            is Waiting -> {
                val p = pairNow()
                if (p?.key != s.key) { stage = Watching; return null }
                val prime = primeAt(s.plan, speed)
                if (pos > prime + SLACK_MS * speed) { Log.i(TAG, "too late for the join at $pos"); stage = Done(s.key); return null }
                if (pos >= prime) {
                    stage = Priming(s.key, s.plan, newTail(p.out, pos + SEEK_START_UP_MS))
                    Log.i(TAG, "tail voice starts at $pos")
                }
                return holdFade(pos)
            }
            is Priming -> return priming(s, pos, speed)
            is Swapping -> return swapping(s)
            is Settling -> return settling(s, pos)
            is Fading -> return fading(s, pos)
        }
        return null
    }

    private fun start(p: Pair, leadMs: Long) {
        val looking = Looking(p.key)
        stage = looking
        val outMs = main.duration
        val outStart = p.out.trim?.startMs ?: 0
        val inStart = p.next.trim?.startMs ?: 0
        val inMs = p.next.trim?.endMs?.let { it - inStart } ?: (p.next.toSong().durationSec * 1000 - inStart).coerceAtLeast(0)
        val fade = fadeMs()
        val beats = beatsAllowed()
        if (!beats) {
            // nothing to read: skipping silence or saving data gets the plain overlap
            val plan = plainJoin(outMs, outMs, inMs, fade, 0)
            Log.i(TAG, "${p.key}: no beats wanted: $plan")
            stage = if (plan != null) Waiting(p.key, plan) else Done(p.key)
            return
        }
        looking.job = scope.launch {
            // a read cut short can't say where the song's sound ends, and its beats would be guessed far ahead: not used
            val out = lookOnce(p.out, (outStart + outMs - STRETCH_MS).coerceAtLeast(outStart), outStart + outMs)?.takeIf { it.whole }?.shifted(-outStart)
            val inn = lookOnce(p.next, inStart, inStart + STRETCH_MS)?.shifted(-inStart)
            if (stage !== looking) return@launch
            val plan = planJoin(out, inn, outMs, inMs, fade, leadMs)
            Log.i(TAG, "${p.key}: ${out?.bpm}/${out?.confidence} to ${inn?.bpm}/${inn?.confidence}: $plan")
            stage = if (plan != null) Waiting(p.key, plan) else Done(p.key)
        }
    }

    private suspend fun lookOnce(item: MediaItem, fromMs: Long, toMs: Long): BeatGrid? {
        val key = "${item.mediaId} $fromMs"
        known[key]?.let { return it }
        val began = System.currentTimeMillis()
        val g = try { look(item, fromMs, toMs) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "couldn't read ${item.mediaId}: $e"); null }
        Log.i(TAG, "read ${item.mediaId} from $fromMs in ${System.currentTimeMillis() - began} ms: ${g?.bpm}")
        // a failure isn't kept: a stream that timed out may read fine next time
        if (g != null && g.whole) { if (known.size > 16) known.clear(); known[key] = g }
        return g
    }

    private fun priming(s: Priming, pos: Long, speed: Float): Float? {
        if (pairNow()?.key != s.key || s.tail.playerError != null || main.playerError != null) { Log.i(TAG, "tail voice dropped before the join"); drop(s.tail); stage = Done(s.key); return null }
        follow(s.tail, lining = !s.inStep)
        val handover = s.plan.startMs - s.plan.leadMs
        // Either player stopping means the silent tail may have run on, so it's measured afresh before any handover, also if it was
        // measuring already. One reading can be a quarter second out: only a big one or the average counts. This can't go round
        // for long: not in step by the slack below, the join is called off.
        val running = s.tail.isPlaying && main.isPlaying
        val off = s.tail.currentPosition - pos
        if (!running || (s.inStep && (abs(off) > SEEK_OVER_MS || s.line.drifted(off, RELINE_MS)))) { s.inStep = false; s.ran = 0; s.line.forget() }
        if (!s.inStep && running && ++s.ran >= SETTLE_TICKS) {
            if (abs(off) > SEEK_OVER_MS) { s.tail.seekTo(pos + SEEK_START_UP_MS); s.ran = 0 }
            else if (s.line.step(s.tail, off, IN_STEP_MS, wanted)) { s.inStep = true; Log.i(TAG, "tail voice in step, off by ${s.line.last} ms") }
        }
        if (s.inStep && pos >= handover) stage = Swapping(s.plan, s.tail)
        else if (!s.inStep && pos > handover + SLACK_MS * speed) { Log.i(TAG, "tail voice wasn't ready"); drop(s.tail); stage = Done(s.key); return null }
        return holdFade(pos)
    }

    private fun swapping(s: Swapping): Float? {
        follow(s.tail)
        val keep = sleepGain()
        if (s.tail.playerError != null) { finish(s.tail, "tail voice failed"); return null }
        if (s.tail.playbackState == Player.STATE_ENDED) { finish(s.tail, "tail voice ended"); return null }
        if (main.isPlaying && !s.tail.isPlaying) {
            if (++s.stalled >= STALL_TICKS) { finish(s.tail, "tail stalled"); return null }
            return if (s.step == 0) 1f else if (s.step == 1) .5f else 0f
        }
        s.stalled = 0
        // the same music on both for a moment, in step, so half of each adds up to one; held while paused
        if (!main.isPlaying) return if (s.step == 0) 1f else if (s.step == 1) .5f else 0f
        val p = pairNow()
        if (p == null) { drop(s.tail); stage = Watching; return null }
        when (s.step++) {
            0 -> { s.tail.volume = .5f * keep; return .5f }
            1 -> { s.tail.volume = keep; return 0f }
            else -> {
                val at = s.plan.cueMs - s.plan.leadMs + (if (s.plan.onBeat) SEEK_START_UP_MS else 0)
                ownSeek = true
                try { main.seekTo(p.nextIndex, at) } finally { ownSeek = false }
                Log.i(TAG, "handed over to ${p.next.mediaId} at $at")
                stage = Settling(s.plan, s.tail)
                return 0f
            }
        }
    }

    private fun settling(s: Settling, pos: Long): Float? {
        follow(s.tail)
        if (s.tail.playerError != null || s.tail.playbackState == Player.STATE_ENDED) { finish(s.tail, "old song ended before the new one played"); return null }
        if (main.isPlaying && !s.tail.isPlaying) {
            if (++s.stalled >= STALL_TICKS) { finish(s.tail, "tail stalled"); return null }
            return 0f
        }
        s.stalled = 0
        s.tail.volume = sleepGain()
        val a = s.tail.currentPosition
        if (main.isPlaying && !s.locked && s.plan.onBeat && ++s.ran >= SETTLE_TICKS) {
            val off = (pos - s.plan.cueMs) - (a - s.plan.startMs)
            if (abs(off) > SEEK_OVER_MS) {
                ownSeek = true
                try { main.seekTo((pos - off + SEEK_START_UP_MS).coerceAtLeast(0)) } finally { ownSeek = false }
                s.ran = 0
            } else s.locked = lineUpMain(s.line, off)
        }
        if (main.isPlaying && a >= s.plan.startMs) {
            Log.i(TAG, "overlap starts, lined up: ${s.locked}, new song at $pos, old at $a")
            // a new song up late fades from here over what's left of the tail, not from partway in, which would jump the gains
            val late = a - s.plan.startMs
            val fade = if (late > LATE_MS) Fading(s.plan, s.tail, pos, (s.plan.lengthMs - late).coerceAtLeast(4L * TICK_MS), s.locked || !s.plan.onBeat, s.line)
            else Fading(s.plan, s.tail, pos - late, s.plan.lengthMs, s.locked || !s.plan.onBeat, s.line)
            stage = fade
        }
        return 0f
    }

    private fun fading(s: Fading, pos: Long): Float? {
        follow(s.tail)
        if (main.playerError != null) {
            // the new song failed with nothing after it: the old song's end isn't lost with it
            if (s.tail.playerError != null || s.tail.playbackState == Player.STATE_ENDED) { finish(s.tail, "old song played out"); return null }
            val to = sleepGain()
            s.tail.volume = (s.tail.volume + to / RAMP_TICKS).coerceAtMost(to)
            return 0f
        }
        val f = ((pos - s.cue).toFloat() / s.lengthMs).coerceIn(0f, 1f)
        if (!s.locked && main.isPlaying) {
            // still catching up: fine while the new song is hardly heard, then it stays where it is
            val off = (pos - s.plan.cueMs) - (s.tail.currentPosition - s.plan.startMs)
            if (f > CATCH_UP_UNTIL) { restoreMain(); s.locked = true; Log.i(TAG, "beats not lined up, last off by ${s.line.last} ms") }
            else s.locked = lineUpMain(s.line, off)
        }
        if (f >= 1f) { finish(s.tail, "overlap done"); return null }
        // equal power: the two together stay about as loud as one
        val cut = 1 - OVERLAP_CUT * sin(f * PI).toFloat()
        s.tail.volume = cos(f * PI / 2).toFloat() * cut * sleepGain()
        return sin(f * PI / 2).toFloat() * cut
    }

    private fun lineUpMain(line: Liner, off: Long): Boolean {
        mainLining = true
        val done = line.step(main, off, LOCKED_MS, wanted)
        if (done) { mainLining = false; Log.i(TAG, "new song on the beat, off by ${line.last} ms") }
        return done
    }

    private fun restoreMain() { if (mainLining) main.playbackParameters = wanted(); mainLining = false }

    /** Pause, a phone call, headphones out: the tail voice does what [main] does. */
    private fun follow(tail: Player, lining: Boolean = false) {
        // idle from a Stop silences it; idle from an error doesn't, so a failed next song doesn't cut off the old one
        val stopped = main.playbackState == Player.STATE_IDLE && main.playerError == null
        val play = main.playWhenReady && main.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE && !stopped
        if (tail.playWhenReady != play) tail.playWhenReady = play
        if (!lining && tail.playbackParameters != wanted()) tail.playbackParameters = wanted()
    }

    private val tail: Player?
        get() = when (val s = stage) { is Priming -> s.tail; is Swapping -> s.tail; is Settling -> s.tail; is Fading -> s.tail; else -> null }

    private fun followNow() { tail?.let { follow(it, lining = (stage as? Priming)?.inStep == false) } }
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { followNow(); wake() }
    override fun onPlaybackSuppressionReasonChanged(reason: Int) = followNow()
    override fun onPlaybackStateChanged(state: Int) = followNow()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { if (!ownSeek) letGo("song changed") }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (!ownSeek && reason != Player.DISCONTINUITY_REASON_SILENCE_SKIP) letGo("position moved")
    }

    private fun letGo(why: String) {
        val busy = stage !== Watching && stage !is Done
        (stage as? Looking)?.job?.cancel()
        tail?.let { Log.i(TAG, "let go: $why"); drop(it) }
        restoreMain()
        stage = Watching
        if (busy) wake()
    }

    private fun finish(tail: Player, why: String) {
        Log.i(TAG, why)
        restoreMain()
        tail.release()
        stage = Watching
    }

    /** A few ticks down to silent, so a let-go doesn't click. */
    private fun drop(tail: Player) { leaving.addLast(tail to intArrayOf(LEAVING_TICKS)) }

    private fun tickLeaving() {
        val it = leaving.iterator()
        while (it.hasNext()) {
            val (tail, left) = it.next()
            if (--left[0] <= 0) { tail.release(); it.remove() } else tail.volume *= left[0].toFloat() / LEAVING_TICKS
        }
    }

    /** The service is going away: everything stops now. */
    fun release() {
        main.removeListener(this)
        (stage as? Looking)?.job?.cancel()
        tail?.release()
        leaving.forEach { it.first.release() }
        leaving.clear()
        stage = Watching
    }
}
