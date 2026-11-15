package com.spotifry.music.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** How two songs overlap. [AUTO] picks one per pair. */
enum class TransitionStyle(val label: String) {
    SILK("Silk blend"), BASS_SWAP("Bass swap"), FILTER_SWEEP("Filter sweep"), AUTO("Auto");

    companion object { fun of(i: Int) = entries.getOrElse(i) { SILK } }
}

const val FILTER_OFF = 0
const val FILTER_HIGHPASS = 1
const val FILTER_LOWPASS = 2

/** What one song does at a moment of the overlap: its gain and a filter ([mode], cutoff [hz]). */
data class Voice(val gain: Float, val mode: Int = FILTER_OFF, val hz: Float = 0f)

/**
 * A pair on the beat for long enough keeps its bass apart (Bass swap); a pair off the beat is dulled instead (Filter sweep),
 * which hides that the beats don't meet; a short one on the beat is just blended.
 */
fun pickStyle(style: TransitionStyle, onBeat: Boolean, lengthMs: Long): TransitionStyle = when {
    style != TransitionStyle.AUTO -> style
    !onBeat -> TransitionStyle.FILTER_SWEEP
    lengthMs >= 6_000 -> TransitionStyle.BASS_SWAP
    else -> TransitionStyle.SILK
}

private fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
private fun logMix(a: Float, b: Float, t: Float) = a * (b / a).pow(t)

/** The old song's and the new song's [Voice] [f] (0..1) of the way through the overlap. Gains are equal power. */
fun transitionVoices(style: TransitionStyle, f: Float): kotlin.Pair<Voice, Voice> {
    val old = cos(f * PI / 2).toFloat(); val new = sin(f * PI / 2).toFloat()
    return when (style) {
        // the old low end goes out as the new one comes in, around the middle, so two basslines never play together
        TransitionStyle.BASS_SWAP -> {
            val a = smooth((f - .4f) / .2f)
            Voice(old, FILTER_HIGHPASS, logMix(20f, 300f, a)) to Voice(new, FILTER_HIGHPASS, logMix(300f, 20f, a))
        }
        TransitionStyle.FILTER_SWEEP ->
            Voice(old, FILTER_LOWPASS, logMix(18_000f, 250f, f)) to Voice(new, FILTER_LOWPASS, logMix(250f, 18_000f, f))
        else -> Voice(old) to Voice(new)
    }
}
