package com.spotifry.desktop

import com.spotifry.music.player.EQ_CENTERS_HZ
import com.spotifry.music.player.EQ_WIDTH_OCTAVES
import com.spotifry.music.player.EqDesign

/** mpv's sound filters for the Sound settings. */
object Sound {
    /**
     * Same loudness: ffmpeg's loudness meter turns each song toward -14 LUFS as it plays.
     * ponytail: measured live, so a song's first seconds can still swell; remember each song's level like the phone if heard
     */
    const val LOUDNESS = "lavfi=[loudnorm=I=-14:TP=-1:LRA=11]"
    /** Skip quiet parts: silences over a second shrink to a third of a second. No start trimming: a song that is silence throughout would never play. */
    const val SKIP_QUIET = "lavfi=[silenceremove=stop_periods=-1:stop_duration=1:stop_silence=0.3:stop_threshold=-50dB]"

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
