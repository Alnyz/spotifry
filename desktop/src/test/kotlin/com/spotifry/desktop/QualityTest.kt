package com.spotifry.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityTest {
    @Test fun lossyShowsTheCodecAndRoundedBitrate() {
        assertEquals("Opus 160 kbps", qualityLabel("opus", 159_800.0, 48_000))
        assertEquals("AAC 128 kbps", qualityLabel("aac", 128_000.0, 44_100))
    }
    @Test fun aLossyFigureThatHighIsAnotherFilesSoOnlyTheNameShows() {
        assertEquals("Opus", qualityLabel("opus", 1_603_000.0, 48_000))
        assertEquals("Opus 512 kbps", qualityLabel("opus", 512_000.0, 48_000))
    }
    @Test fun lossyWithoutBitrateShowsJustTheName() = assertEquals("Opus", qualityLabel("opus", null, 48_000))
    @Test fun losslessIsHiResOnlyAboveCdRates() {
        assertEquals("Lossless", qualityLabel("flac", 900_000.0, 44_100))
        assertEquals("Hi-Res Lossless", qualityLabel("flac", 2_000_000.0, 96_000))
        // 24-bit at 44.1 kHz, which mpv decodes to s32: Hi-Res, as the phone says
        assertEquals("Hi-Res Lossless", qualityLabel("flac", 1_600_000.0, 44_100, "s32"))
        assertEquals("Lossless", qualityLabel("flac", 900_000.0, 44_100, "s16"))
    }
    @Test fun unknownIsNull() { assertNull(qualityLabel(null, null, null)); assertNull(qualityLabel("pcm_s16le", 1.0, 44_100)) }
}
