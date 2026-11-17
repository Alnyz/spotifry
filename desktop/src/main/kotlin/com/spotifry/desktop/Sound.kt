package com.spotifry.desktop

import com.spotifry.music.player.EqShape
import com.spotifry.music.player.octavesOf
import com.spotifry.music.player.EqDesign
import com.spotifry.music.player.FILTER_HIGHPASS
import com.spotifry.music.player.SilenceRules
import com.spotifry.music.player.SpatialSetting
import com.spotifry.music.player.FineTune
import com.spotifry.music.player.FineTuneSetting
import kotlin.math.pow

/** mpv's sound filters for the Sound settings. */
object Sound {
    /**
     * Same loudness: ffmpeg's loudness meter turns each song toward -14 LUFS as it plays.
     * ponytail: measured live, so a song's first seconds can still swell; remember each song's level like the phone if heard
     */
    const val LOUDNESS = "lavfi=[loudnorm=I=-14:TP=-1:LRA=11]"
    /** Left and right mixed into one, sent to both ears. The format step makes any layout stereo first. */
    const val MONO = "lavfi=[aformat=channel_layouts=stereo,pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1]"

    private fun num(x: Double, digits: Int = 4) = "%.${digits}f".format(java.util.Locale.US, x)

    /**
     * Skip silence: silences over a second shrink. [db] is how quiet counts as silence; [cutPercent] is the share of a
     * silence taken out, as on the phone. No start trimming: a song that is silence throughout would never play.
     * What is left of a silence is 1.5 s per unit kept, so the default 80% leaves 0.3 s, as before, and never more than the 1 s that starts a cut.
     */
    fun skipQuiet(db: Int = SilenceRules.DEFAULT_DB, cutPercent: Int = SilenceRules.DEFAULT_CUT_PERCENT): String {
        val kept = (SilenceRules.retention(cutPercent) * 1.5).coerceAtMost(1.0)
        return "lavfi=[silenceremove=stop_periods=-1:stop_duration=1:stop_silence=${num(kept, 2)}:stop_threshold=${db.coerceIn(SilenceRules.MIN_DB, SilenceRules.MAX_DB)}dB]"
    }

    /**
     * Headphone crossfeed with the phone's maths: each ear keeps `1 - mix + mix / (1 + g)` of itself and takes `mix * g / (1 + g)`
     * of the other side, dulled by the same one-pole filter (`lowpass` with one pole is exactly it). Null when [s] mixes nothing in.
     */
    fun spatial(s: SpatialSetting): String? {
        val mix = s.amount.coerceIn(0, 100) / 100.0
        if (mix == 0.0) return null
        val g = 10.0.pow(-s.crossDb / 20.0)
        val own = 1 - mix + mix / (1 + g)
        val cross = mix * g / (1 + g)
        return "lavfi=[aformat=channel_layouts=stereo,asplit[a][b];" +
            "[a]pan=stereo|c0=${num(own)}*c0|c1=${num(own)}*c1[d];" +
            "[b]pan=stereo|c0=${num(cross)}*c1|c1=${num(cross)}*c0,lowpass=f=${s.shadowHz}:p=1[x];" +
            "[d][x]amix=inputs=2:normalize=0:duration=longest]"
    }

    /**
     * The Tuning tab as filters, with the phone's maths ([com.spotifry.music.player.FineTune]): one `pan` does width (mid/side)
     * and balance (far side down), as both are plain per-channel sums; then the RBJ high- and low-pass at Q 0.707, night
     * mode's compressor (peak, both sides linked, 10/250 ms), the gain, and the limiter at -1 dB, which night mode always has.
     * Null when neutral, so nothing is added. A filter missing from this mpv ([has] says no) is left out, never breaking playback.
     */
    fun tune(t: FineTuneSetting, has: (String) -> Boolean = { true }): String? {
        if (t.neutral) return null
        val steps = buildList {
            if ((t.width != 100 || t.balance != 0) && has("pan")) {
                val k = t.width / 100.0
                val same = (1 + k) / 2; val other = (1 - k) / 2
                val far = 1 - kotlin.math.abs(t.balance) / 100.0
                val l = if (t.balance > 0) far else 1.0; val r = if (t.balance < 0) far else 1.0
                // above 100% width the other side is taken away; pan wants the sign between terms, and its own side first, which is never below 0
                fun mix(own: Double, cross: Double, o: String, x: String) = "${num(own)}*$o${if (cross < 0) "-" else "+"}${num(kotlin.math.abs(cross))}*$x"
                add("aformat=channel_layouts=stereo,pan=stereo|c0=${mix(l * same, l * other, "c0", "c1")}|c1=${mix(r * same, r * other, "c1", "c0")}")
            }
            if (t.highPassHz > 0 && has("highpass")) add("highpass=f=${t.highPassHz}:p=2:t=q:w=0.707")
            if (t.lowPassHz > 0 && has("lowpass")) add("lowpass=f=${t.lowPassHz}:p=2:t=q:w=0.707")
            if (t.night > 0 && has("acompressor")) {
                val (thresholdDb, ratio, makeupDb) = if (t.night == 1) Triple(-24.0, 2, 6.0) else Triple(-30.0, 4, 14.0)
                add("acompressor=threshold=${num(10.0.pow(thresholdDb / 20))}:ratio=$ratio:attack=10:release=250:makeup=${num(10.0.pow(makeupDb / 20))}:knee=1:link=maximum:detection=peak")
            }
            if (t.gainDb != 0 && has("volume")) add("volume=${t.gainDb}dB")
            // level=0: only a ceiling, no levelling of its own
            if ((t.limiter || t.night > 0) && has("alimiter")) add("alimiter=limit=${num(FineTune.CEILING)}:attack=1:release=100:level=0")
        }
        return if (steps.isEmpty()) null else "lavfi=[${steps.joinToString(",")}]"
    }

    /** The sound filters this mpv's ffmpeg has, read once from `mpv --af=help`; empty when it can't be asked, which lets every filter through. */
    fun available(binary: String = Platform.tool("mpv")): Set<String> = runCatching {
        val p = ProcessBuilder(binary, "--no-config", "--af=help").redirectErrorStream(true).start()
        val names = p.inputStream.bufferedReader().readLines().mapNotNull { Regex("^\\s+([a-z0-9_]+)\\s").find(it)?.groupValues?.get(1) }.toSet()
        p.waitFor(); names
    }.getOrDefault(emptySet())

    /** The filter a crossfade changes while it runs: two 12 dB steps, 24 dB per octave like the phone's. [mode] is FILTER_HIGHPASS or FILTER_LOWPASS. */
    fun sweep(mode: Int, hz: Float): String { val f = "${sweepName(mode)}=f=${num(hz.toDouble(), 1)}:p=2"; return "lavfi=[$f,$f]" }

    /** The filter's name, which mpv's `af-command` aims at to move the cutoff while the sound plays. */
    fun sweepName(mode: Int) = if (mode == FILTER_HIGHPASS) "highpass" else "lowpass"

    /**
     * The equalizer at [levelsMb]: the same peak filters as the phone (ffmpeg's `equalizer` is the same RBJ filter with the
     * same octave width), each set to [EqDesign]'s corrected gain, then turned down by its preamp so a boost can't clip.
     * Null when flat. Gains are worked out for 48 kHz; at 44.1 kHz the top band lands a fraction of a dB off.
     */
    fun equalizer(levelsMb: List<Int>, shape: EqShape = EqShape.DEFAULT): String? {
        if (levelsMb.all { it == 0 }) return null
        val d = EqDesign(levelsMb, shape = shape)
        val bands = d.gainsDb.withIndex().filter { kotlin.math.abs(it.value) >= .01 }
            .map { (i, g) -> "equalizer=f=${shape.hz[i]}:t=o:w=${octavesOf(shape.q[i])}:g=${"%.2f".format(java.util.Locale.US, g)}" }
        return "lavfi=[${(bands + "volume=${"%.4f".format(java.util.Locale.US, d.preamp)}").joinToString(",")}]"
    }
}
