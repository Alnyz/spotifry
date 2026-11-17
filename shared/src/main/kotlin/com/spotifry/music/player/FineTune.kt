package com.spotifry.music.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * The small sound controls: [balance] -100 (left) to 100 (right), [width] 0 (mono) to 200 (twice as wide) with 100 as
 * recorded, plain [gainDb] -15..15, a [limiter] that keeps peaks under -1 dB, a [highPassHz] and [lowPassHz] that cut
 * what is below or above them (0 is off), and [night]: 0 off, 1 light, 2 strong, which evens out loud and quiet parts.
 */
data class FineTuneSetting(
    val balance: Int = 0, val width: Int = 100,
    val gainDb: Int = 0, val limiter: Boolean = false,
    val highPassHz: Int = 0, val lowPassHz: Int = 0, val night: Int = 0,
) {
    /** True when every control is where it started, so the sound can pass untouched. */
    val neutral get() = this == FineTuneSetting()
}

/** Samples -1..1 one frame at a time; results are left in [outL] and [outR]. */
class FineTune {
    var setting = FineTuneSetting()
        set(v) { val redo = field.highPassHz != v.highPassHz || field.lowPassHz != v.lowPassHz; field = v; if (redo) design() }
    var outL = 0f; private set
    var outR = 0f; private set
    private var rate = 44_100
    private var gain = 1.0
    private var level = 0.0
    /** b0, b1, b2, a1, a2 for the high-pass and the low-pass. */
    private val co = DoubleArray(10)
    /** x1, x2, y1, y2 for each of the two filters on each of the two channels. */
    private val st = DoubleArray(16)

    fun configure(sampleRate: Int) { rate = sampleRate; st.fill(0.0); gain = 1.0; level = 0.0; design() }

    private fun design() {
        pass(0, setting.highPassHz, high = true)
        pass(1, setting.lowPassHz, high = false)
    }

    // the RBJ cookbook second-order filters, Q 0.707; a cut-off too close to half the sample rate is left off
    private fun pass(i: Int, hz: Int, high: Boolean) {
        if (hz <= 0 || hz >= rate * 0.45) { co.fill(0.0, i * 5, i * 5 + 5); return }
        val w = 2 * PI * hz / rate; val c = cos(w); val al = sin(w) / (2 * 0.7071)
        val a0 = 1 + al
        val b0 = (if (high) 1 + c else 1 - c) / 2 / a0
        val b1 = (if (high) -(1 + c) else 1 - c) / a0
        co[i * 5] = b0; co[i * 5 + 1] = b1; co[i * 5 + 2] = b0; co[i * 5 + 3] = -2 * c / a0; co[i * 5 + 4] = (1 - al) / a0
    }

    private fun filter(x: Double, f: Int, ch: Int): Double {
        val o = f * 5
        if (co[o] == 0.0) return x
        val s = (ch * 2 + f) * 4
        val y = co[o] * x + co[o + 1] * st[s] + co[o + 2] * st[s + 1] - co[o + 3] * st[s + 2] - co[o + 4] * st[s + 3]
        st[s + 1] = st[s]; st[s] = x; st[s + 3] = st[s + 2]; st[s + 2] = y
        return y
    }

    fun process(l: Float, r: Float) {
        val t = setting
        var a = l.toDouble(); var b = r.toDouble()
        if (t.width != 100) { val m = (a + b) / 2; val s = (a - b) / 2 * (t.width / 100.0); a = m + s; b = m - s }
        if (t.highPassHz > 0 || t.lowPassHz > 0) { for (f in 0..1) { a = filter(a, f, 0); b = filter(b, f, 1) } }
        // balance takes the far side down and leaves the near one alone
        if (t.balance != 0) { val k = 1 - abs(t.balance) / 100.0; if (t.balance > 0) a *= k else b *= k }
        if (t.night > 0) {
            val (threshold, ratio, makeupDb) = if (t.night == 1) LIGHT else STRONG
            val peak = max(abs(a), abs(b))
            // the level follows the peak up in 10 ms and down in 250 ms
            level = if (peak > level) peak + (level - peak) * exp(-1.0 / (rate * 0.010)) else peak + (level - peak) * exp(-1.0 / (rate * 0.250))
            val db = 20 * ln(level.coerceAtLeast(1e-6)) / ln(10.0)
            val over = db - threshold
            val reduceDb = if (over > 0) over * (1 - 1 / ratio) else 0.0
            val g = 10.0.pow((makeupDb - reduceDb) / 20)
            a *= g; b *= g
        }
        val g = 10.0.pow(t.gainDb / 20.0)
        a *= g; b *= g
        // night mode lifts the quiet parts, so it always has the limiter behind it
        if (t.limiter || t.night > 0) {
            val peak = max(abs(a), abs(b))
            val want = if (peak > CEILING) CEILING / peak else 1.0
            // down at once, so nothing gets past; back up over about a tenth of a second
            gain = if (want < gain) want else gain + (1 - gain) * (1.0 / (rate * 0.1))
            a *= gain; b *= gain
        } else gain = 1.0
        outL = a.toFloat(); outR = b.toFloat()
    }

    companion object {
        const val CEILING = 0.891
        /** Threshold in dB, ratio, and the lift in dB that brings the loud parts back to where they were. */
        private val LIGHT = Triple(-24.0, 2.0, 6.0)
        private val STRONG = Triple(-30.0, 4.0, 14.0)
    }
}
