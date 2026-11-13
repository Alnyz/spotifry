package com.spotifry.music.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityTest {
    private fun fmt(mime: String, b: Format.Builder.() -> Unit = {}) = Format.Builder().setSampleMimeType(mime).apply(b).build()

    @Test fun youtubeOpusTakesYoutubesBitrate() = assertEquals("Opus 160 kbps", qualityLabel(fmt(MimeTypes.AUDIO_OPUS) { setSampleRate(48000) }, 160))
    @Test fun opusWithNoBitrateAnywhereIsJustOpus() = assertEquals("Opus", qualityLabel(fmt(MimeTypes.AUDIO_OPUS), null))
    @Test fun fileBitrateWins() = assertEquals("AAC 129 kbps", qualityLabel(fmt(MimeTypes.AUDIO_AAC) { setAverageBitrate(129_400) }, 160))
    @Test fun hiResFlac() = assertEquals("Hi-Res Lossless", qualityLabel(fmt(MimeTypes.AUDIO_FLAC) { setPcmEncoding(C.ENCODING_PCM_24BIT); setSampleRate(96000) }))
    @Test fun cdFlac() = assertEquals("Lossless", qualityLabel(fmt(MimeTypes.AUDIO_FLAC) { setPcmEncoding(C.ENCODING_PCM_16BIT); setSampleRate(44100) }))
    @Test fun losslessNeverGetsBitrate() = assertEquals("Lossless", qualityLabel(fmt(MimeTypes.AUDIO_FLAC) { setAverageBitrate(900_000) }, 160))
    @Test fun cdAtHigherRateIsHiRes() = assertEquals("Hi-Res Lossless", qualityLabel(fmt(MimeTypes.AUDIO_FLAC) { setPcmEncoding(C.ENCODING_PCM_16BIT); setSampleRate(96000) }))
    @Test fun unknownFormat() { assertNull(qualityLabel(fmt("audio/x-unknown"))); assertNull(qualityLabel(null)) }
    @Test fun aLossyFileNeverReadsAsLossless() = assertEquals("Opus", qualityLabel(fmt(MimeTypes.AUDIO_OPUS), 1603))
    @Test fun aNewSongShowsNoLabelUntilItsTracksAreReady() {
        assertNull(qualityToShow(false, "Opus", "Opus 160 kbps", sameSong = false))
        assertEquals("Opus 160 kbps", qualityToShow(false, "Opus", "Opus 160 kbps", sameSong = true))
        assertEquals("Hi-Res Lossless", qualityToShow(true, "Hi-Res Lossless", "Opus 160 kbps", sameSong = false))
    }
    @Test fun aCutShortOrUnreadableCopyIsBroken() {
        assertEquals(true, savedCopyBroken(androidx.media3.exoplayer.ExoPlaybackException.createForSource(java.io.EOFException(), 1000)))
        assertEquals(true, savedCopyBroken(RuntimeException(androidx.media3.common.ParserException.createForMalformedContainer("x", null))))
        assertEquals(false, savedCopyBroken(java.io.IOException("offline")))
    }
}
