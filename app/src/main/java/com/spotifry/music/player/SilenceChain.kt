package com.spotifry.music.player

/**
 * The sound chain with our own silence skipper in place of the player's; [androidx.media3.exoplayer.ExoPlayer.setSkipSilenceEnabled]
 * still switches it. The skipper's numbers can't change once built, so when [rules] (dB, cut percent) has changed it is
 * rebuilt the next time the sink sets up for a song: a new value is heard from the next song.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SilenceChain(
    private val before: Array<androidx.media3.common.audio.AudioProcessor>,
    private val rules: () -> kotlin.Pair<Int, Int> = { SilenceRules.DEFAULT_DB to SilenceRules.DEFAULT_CUT_PERCENT },
) : androidx.media3.exoplayer.audio.DefaultAudioSink.AudioProcessorChain {
    private var built = rules()
    private var inner = make()
    private var skip = false
    private var params = androidx.media3.common.PlaybackParameters.DEFAULT

    private fun make() = androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain(before, newSilenceSkipper(built.first, built.second), androidx.media3.common.audio.SonicAudioProcessor())

    override fun getAudioProcessors(): Array<androidx.media3.common.audio.AudioProcessor> {
        val now = rules()
        if (now != built) { built = now; inner = make(); inner.applyPlaybackParameters(params); inner.applySkipSilenceEnabled(skip) }
        return inner.audioProcessors
    }
    override fun applyPlaybackParameters(p: androidx.media3.common.PlaybackParameters): androidx.media3.common.PlaybackParameters { params = p; return inner.applyPlaybackParameters(p) }
    override fun applySkipSilenceEnabled(on: Boolean): Boolean { skip = on; return inner.applySkipSilenceEnabled(on) }
    override fun getMediaDuration(playoutDuration: Long) = inner.getMediaDuration(playoutDuration)
    override fun getSkippedOutputFrameCount() = inner.skippedOutputFrameCount
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun newSilenceSkipper(db: Int = SilenceRules.DEFAULT_DB, cutPercent: Int = SilenceRules.DEFAULT_CUT_PERCENT) = androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor(
    SilenceRules.MIN_SILENCE_US, SilenceRules.retention(cutPercent), SilenceRules.MAX_KEPT_US, SilenceRules.MIN_VOLUME_PERCENT, SilenceRules.level(db),
)
