package com.spotifry.music.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the player says about the sound it plays now. Null where it has not said yet. */
data class AudioReadings(
    /** The file's own format, before decoding. */
    val mime: String? = null, val sampleRate: Int? = null, val channels: Int? = null, val bitrateKbps: Int? = null,
    val decoder: String? = null,
    /** What the decoder hands on: the encoding of the raw sound ([C.ENCODING_PCM_16BIT] and so on). */
    val decodedEncoding: Int? = null,
    /** The player's output stream, after Spotifry's own steps: what is sent to Android. */
    val outEncoding: Int? = null, val outRate: Int? = null, val outChannelMask: Int? = null, val outBufferBytes: Int? = null, val offload: Boolean = false,
)

/** Filled by the playing service from the player's own reports, read by the Audio pipeline sheet. One per app. */
@OptIn(UnstableApi::class)
class AudioTap {
    private val _readings = MutableStateFlow(AudioReadings())
    val readings: StateFlow<AudioReadings> = _readings
    /** The loudness step's gain now, in dB; set by the service. Null while it is not in use. */
    @Volatile var gainDb: () -> Float? = { null }

    fun attach(player: ExoPlayer) {
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                _readings.value = _readings.value.copy(
                    mime = format.sampleMimeType, sampleRate = format.sampleRate.takeIf { it > 0 }, channels = format.channelCount.takeIf { it > 0 },
                    bitrateKbps = (format.averageBitrate.takeIf { it > 0 } ?: format.bitrate.takeIf { it > 0 })?.div(1000),
                    decodedEncoding = format.pcmEncoding.takeIf { it != Format.NO_VALUE } ?: C.ENCODING_PCM_16BIT,
                )
            }
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                _readings.value = _readings.value.copy(decoder = decoderName)
            }
            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                _readings.value = _readings.value.copy(
                    outEncoding = audioTrackConfig.encoding, outRate = audioTrackConfig.sampleRate, outChannelMask = audioTrackConfig.channelConfig,
                    outBufferBytes = audioTrackConfig.bufferSize, offload = audioTrackConfig.offload,
                )
            }
        })
    }
}
