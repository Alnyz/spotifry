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
/** Analysis starts this long before the end: a slow connection needs the time, and it only reads what's about to play anyway. */
private const val LOOK_BEFORE_END_MS = 90_000L
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
private const val SEEK_START_UP_MS = 350L
private const val LEAVING_TICKS = 3
private const val TICK_MS = 50

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
            slewing = (abs(last) / CATCH_UP / TICK_MS).toInt().coerceAtLeast(1)
            p.playbackParameters = PlaybackParameters(b.speed * (if (last < 0) 1 + CATCH_UP else 1 - CATCH_UP), b.pitch)
            return false
        }
    }

    private sealed interface Stage
    private data object Watching : Stage
    private class Done(val key: String) : Stage
    private class Looking(val key: String) : Stage { var job: Job? = null }
    private class Waiting(val key: String, val plan: JoinPlan) : Stage
    private class Priming(val key: String, val plan: JoinPlan, val tail: Player) : Stage { var ran = 0; var inStep = false; val line = Liner() }
    private class Swapping(val plan: JoinPlan, val tail: Player) : Stage { var step = 0 }
    private class Settling(val plan: JoinPlan, val tail: Player) : Stage { var ran = 0; var locked = false; val line = Liner() }
    private class Fading(val plan: JoinPlan, val tail: Player, val cue: Long, var locked: Boolean, val line: Liner) : Stage

    private var stage: Stage = Watching
    private val leaving = ArrayDeque<kotlin.Pair<Player, IntArray>>()
    private var ownSeek = false
    /** [main]'s real speed while it runs a little off it to line up the beats. */
    private var mainBase: PlaybackParameters? = null
    private val known = HashMap<String, BeatGrid>()

    init { main.addListener(this) }

    /** Nothing under way, so this can be let go when the setting goes off. */
    val idle get() = leaving.isEmpty() && (stage === Watching || stage is Done)

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
        val speed = main.playbackParameters.speed
        val pos = main.currentPosition
        when (val s = stage) {
            Watching -> {
                val p = pairNow() ?: return null
                val left = main.duration - pos
                if (main.duration == C.TIME_UNSET || !main.isPlaying || left > LOOK_BEFORE_END_MS * speed) return null
                if (left < MIN_LEFT_MS * speed) { stage = Done(p.key); return null }
                start(p, (JOIN_LEAD_MS * speed).toLong())
            }
            is Done -> if (pairNow()?.key != s.key) stage = Watching
            is Looking -> if (pairNow()?.key != s.key) { s.job?.cancel(); stage = Watching }
            is Waiting -> {
                val p = pairNow()
                if (p?.key != s.key) { stage = Watching; return null }
                val handover = s.plan.startMs - s.plan.leadMs
                val prime = handover - (PRIME_MS * speed).toLong()
                if (pos > prime + SLACK_MS * speed) { Log.i(TAG, "too late for the join at $pos"); stage = Done(s.key); return null }
                if (pos >= prime) {
                    stage = Priming(s.key, s.plan, newTail(p.out, pos + SEEK_START_UP_MS))
                    Log.i(TAG, "tail voice starts at $pos")
                }
                // the plain fade doesn't start on a song about to be handed over
                return 1f
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
        looking.job = scope.launch {
            val out = if (beats) lookOnce(p.out, (outStart + outMs - STRETCH_MS).coerceAtLeast(outStart), outStart + outMs)?.shifted(-outStart) else null
            val inn = lookOnce(p.next, inStart, inStart + STRETCH_MS)?.shifted(-inStart)
            if (stage !== looking) return@launch
            // fed with no grid for the old song, the plan is a plain overlap
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
        if (g != null) { if (known.size > 16) known.clear(); known[key] = g }
        return g
    }

    private fun priming(s: Priming, pos: Long, speed: Float): Float? {
        if (pairNow()?.key != s.key || s.tail.playerError != null) { Log.i(TAG, "tail voice dropped before the join"); drop(s.tail); stage = Done(s.key); return null }
        follow(s.tail, lining = !s.inStep)
        val handover = s.plan.startMs - s.plan.leadMs
        if (!s.inStep && s.tail.isPlaying && main.isPlaying && ++s.ran >= SETTLE_TICKS) {
            val off = s.tail.currentPosition - pos
            if (abs(off) > SEEK_OVER_MS) { s.tail.seekTo(pos + SEEK_START_UP_MS); s.ran = 0 }
            else if (s.line.step(s.tail, off, IN_STEP_MS) { main.playbackParameters }) { s.inStep = true; Log.i(TAG, "tail voice in step, off by ${s.line.last} ms") }
        }
        if (s.inStep && pos >= handover) stage = Swapping(s.plan, s.tail)
        else if (!s.inStep && pos > handover + SLACK_MS * speed) { Log.i(TAG, "tail voice wasn't ready"); drop(s.tail); stage = Done(s.key); return null }
        return 1f
    }

    private fun swapping(s: Swapping): Float? {
        follow(s.tail)
        val keep = sleepGain()
        // the same music on both for a moment; held while paused
        if (!main.isPlaying) return if (s.step == 0) 1f else if (s.step == 1) .7f else 0f
        val p = pairNow()
        if (p == null) { drop(s.tail); stage = Watching; return null }
        when (s.step++) {
            0 -> { s.tail.volume = .7f * keep; return .7f }
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
            stage = Fading(s.plan, s.tail, pos - (a - s.plan.startMs), s.locked || !s.plan.onBeat, s.line)
        }
        return 0f
    }

    private fun fading(s: Fading, pos: Long): Float? {
        follow(s.tail)
        val f = ((pos - s.cue).toFloat() / s.plan.lengthMs).coerceIn(0f, 1f)
        if (!s.locked && main.isPlaying) {
            // still catching up: fine while the new song is hardly heard, then it stays where it is
            val off = (pos - s.plan.cueMs) - (s.tail.currentPosition - s.plan.startMs)
            if (f > .33f) { restoreMain(); s.locked = true; Log.i(TAG, "beats not lined up, last off by ${s.line.last} ms") }
            else s.locked = lineUpMain(s.line, off)
        }
        if (f >= 1f) { finish(s.tail, "overlap done"); return null }
        // equal power: the two together stay about as loud as one
        s.tail.volume = cos(f * PI / 2).toFloat() * sleepGain()
        return sin(f * PI / 2).toFloat()
    }

    private fun lineUpMain(line: Liner, off: Long): Boolean {
        val base = mainBase ?: main.playbackParameters.also { mainBase = it }
        val done = line.step(main, off, LOCKED_MS) { base }
        if (done) { mainBase = null; Log.i(TAG, "new song on the beat, off by ${line.last} ms") }
        return done
    }

    private fun restoreMain() { mainBase?.let { main.playbackParameters = it }; mainBase = null }

    /** Pause, a phone call, headphones out: the tail voice does what [main] does. */
    private fun follow(tail: Player, lining: Boolean = false) {
        val play = main.playWhenReady && main.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE && main.playbackState != Player.STATE_IDLE
        if (tail.playWhenReady != play) tail.playWhenReady = play
        val base = mainBase ?: main.playbackParameters
        if (!lining && tail.playbackParameters != base) tail.playbackParameters = base
    }

    private val tail: Player?
        get() = when (val s = stage) { is Priming -> s.tail; is Swapping -> s.tail; is Settling -> s.tail; is Fading -> s.tail; else -> null }

    private fun followNow() { tail?.let { follow(it, lining = (stage as? Priming)?.inStep == false) } }
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = followNow()
    override fun onPlaybackSuppressionReasonChanged(reason: Int) = followNow()
    override fun onPlaybackStateChanged(state: Int) = followNow()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { if (!ownSeek) letGo("song changed") }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (!ownSeek && reason != Player.DISCONTINUITY_REASON_SILENCE_SKIP) letGo("position moved")
    }

    private fun letGo(why: String) {
        (stage as? Looking)?.job?.cancel()
        tail?.let { Log.i(TAG, "let go: $why"); drop(it) }
        restoreMain()
        stage = Watching
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
