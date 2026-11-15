package com.spotifry.desktop

import com.spotifry.music.player.EQ_CENTERS_HZ
import com.spotifry.music.player.EQ_WIDTH_OCTAVES
import com.spotifry.music.player.EqDesign
import com.spotifry.music.player.FILTER_HIGHPASS
import com.spotifry.music.player.SilenceRules
import com.spotifry.music.player.SpatialSetting
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
     * Skip quiet parts: silences over a second shrink. [db] is how quiet counts as silence; [cutPercent] is the share of a
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

    /** The filter a crossfade changes while it runs: two 12 dB steps, 24 dB per octave like the phone's. [mode] is FILTER_HIGHPASS or FILTER_LOWPASS. */
    fun sweep(mode: Int, hz: Float): String { val f = "${sweepName(mode)}=f=${num(hz.toDouble(), 1)}:p=2"; return "lavfi=[$f,$f]" }

    /** The filter's name, which mpv's `af-command` aims at to move the cutoff while the sound plays. */
    fun sweepName(mode: Int) = if (mode == FILTER_HIGHPASS) "highpass" else "lowpass"

    /**
     * The equalizer at [levelsMb]: the same peak filters as the phone (ffmpeg's `equalizer` is the same RBJ filter with the
     * same octave width), each set to [EqDesign]'s corrected gain, then turned down by its preamp so a boost can't clip.
     * Null when flat. Gains are worked out for 48 kHz; at 44.1 kHz the top band lands a fraction of a dB off.
     */
    fun equalizer(levelsMb: List<Int>): String? {
        if (levelsMb.all { it == 0 }) return null
        val d = EqDesign(levelsMb)
        val bands = d.gainsDb.withIndex().filter { kotlin.math.abs(it.value) >= .01 }
            .map { (i, g) -> "equalizer=f=${EQ_CENTERS_HZ[i]}:t=o:w=$EQ_WIDTH_OCTAVES:g=${"%.2f".format(java.util.Locale.US, g)}" }
        return "lavfi=[${(bands + "volume=${"%.4f".format(java.util.Locale.US, d.preamp)}").joinToString(",")}]"
    }
}
