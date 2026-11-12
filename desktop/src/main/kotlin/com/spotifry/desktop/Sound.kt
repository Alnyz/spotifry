package com.spotifry.desktop

/** mpv's sound filters for the Sound settings. */
object Sound {
    /**
     * Same loudness: ffmpeg's loudness meter turns each song toward -14 LUFS as it plays.
     * ponytail: measured live, so a song's first seconds can still swell; remember each song's level like the phone if heard
     */
    const val LOUDNESS = "lavfi=[loudnorm=I=-14:TP=-1:LRA=11]"
    /** Skip quiet parts: silences over a second shrink to a third of a second. No start trimming: a song that is silence throughout would never play. */
    const val SKIP_QUIET = "lavfi=[silenceremove=stop_periods=-1:stop_duration=1:stop_silence=0.3:stop_threshold=-50dB]"
}
