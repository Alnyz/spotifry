package com.spotifry.music.ui.nowplaying

import androidx.media3.common.C
import com.spotifry.music.player.AudioReadings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatSummaryTest {
    @Test fun saysTheFileItsDepthAndRate() =
        assertEquals("FLAC · 24-bit · 96 kHz", formatSummary(AudioReadings(mime = "audio/flac", sampleRate = 96_000, decodedEncoding = C.ENCODING_PCM_24BIT)))

    @Test fun leavesOutWhatIsNotKnown() = assertEquals("Opus · 48 kHz", formatSummary(AudioReadings(mime = "audio/opus", sampleRate = 48_000)))

    @Test fun nothingBeforeASongPlays() = assertNull(formatSummary(AudioReadings()))
}
