package com.spotifry.music.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPipelineTest {
    private val opus = AudioReadings(
        mime = "audio/opus", sampleRate = 48_000, channels = 2, bitrateKbps = 145, decoder = "c2.android.opus.decoder", decodedEncoding = C.ENCODING_PCM_16BIT,
        outEncoding = C.ENCODING_PCM_FLOAT, outRate = 48_000, outChannelMask = 12, outBufferBytes = 192_000,
    )
    private val speaker = OutputInfo(OutputKind.Phone, "Phone speaker", null, null)
    private fun input(r: AudioReadings = opus, o: OutputInfo = speaker, eqOn: Boolean = false, leveling: Boolean = false, mixer: Int? = 48_000, direct: Int? = 0) = PipelineInput(
        origin = "youtube", readings = r, bitrateKbps = null, eqOn = eqOn, eqName = AUTOEQ_PREFIX + "HD 600", leveling = leveling, gainDb = -2.14f, fadeSeconds = 0, speed = 1f, pitch = 0,
        trimText = null, skipSilence = false, spatial = false, mixerRateHz = mixer, directSupport = direct, output = o,
    )
    private fun row(groups: List<PipelineGroup>, group: String, title: String) = groups.first { it.name == group }.rows.first { it.title == title }

    @Test fun `four groups in order, nothing of Spotifry's lit when everything is off`() {
        val g = pipelineGroups(input())
        assertEquals(listOf("Input", "Spotifry", "Android", "Output"), g.map { it.name })
        assertFalse(g[1].lit)
        assertFalse(g[2].lit)
    }

    @Test fun `the file and the decoder are described in plain numbers`() {
        val g = pipelineGroups(input())
        assertEquals("YouTube", row(g, "Input", "Source").detail)
        assertEquals("48 kHz · Stereo · 145 kbps", row(g, "Input", "Opus").detail)
        assertEquals("c2.android.opus.decoder · software\nGives out 16-bit PCM · 1,536 kbps", row(g, "Input", "Decoder").detail)
    }

    @Test fun `equalizer and same volume light the Spotifry group and its rows`() {
        val g = pipelineGroups(input(eqOn = true, leveling = true))
        assertTrue(g[1].lit)
        assertEquals("HD 600", row(g, "Spotifry", "Equalizer").detail)
        assertTrue(row(g, "Spotifry", "Equalizer").hot)
        assertEquals("On · −2.1 dB", row(g, "Spotifry", "Same volume for every song").detail)
        assertEquals("32-bit float PCM", row(g, "Spotifry", "Processing format").detail)
    }

    @Test fun `a rate change is Android's, lights its group and not Spotifry's`() {
        val g = pipelineGroups(input(r = opus.copy(outRate = 96_000), mixer = 48_000))
        assertTrue(g[2].lit)
        assertFalse(g[1].lit)
        assertEquals("96 → 48 kHz", row(g, "Android", "Phone mixer").detail)
        assertEquals("48 → 48 kHz, no change", row(pipelineGroups(input()), "Android", "Phone mixer").detail)
        assertEquals("44.1 kHz", hzText(44_100))
    }

    @Test fun `bluetooth names its codec, and a missing codec shows Bluetooth alone`() {
        val aac = OutputInfo(OutputKind.Bluetooth, "WH-1000XM5", null, null, BtCodec("AAC", 44_100, 16))
        assertEquals("Bluetooth · AAC", pipelineGroups(input(o = aac))[3].rows[0].detail)
        assertEquals("AAC · 44.1 kHz · 16-bit", row(pipelineGroups(input(o = aac)), "Output", "Device supports").detail)
        assertEquals("Bluetooth · codec set by your phone", pipelineGroups(input(o = aac.copy(codec = null)))[3].rows[0].detail)
        assertEquals("Set by your phone", row(pipelineGroups(input(o = aac.copy(codec = null))), "Output", "Device supports").detail)
    }

    @Test fun `what is not known shows a dash`() {
        val g = pipelineGroups(input(r = AudioReadings(), mixer = null, direct = null))
        assertEquals("—", row(g, "Spotifry", "Processing format").detail)
        assertEquals("—", row(g, "Android", "Phone mixer").detail)
        assertEquals("—", row(g, "Android", "Direct path").detail)
        assertEquals("—", row(g, "Output", "Receives").detail)
        assertEquals("—", row(g, "Output", "Device supports").detail)
    }

    @Test fun `direct path says supported, active or mixed`() {
        assertEquals("Not supported · mixed path", row(pipelineGroups(input(direct = 0)), "Android", "Direct path").detail)
        assertEquals("Available, not in use", row(pipelineGroups(input(direct = 1)), "Android", "Direct path").detail)
        assertEquals("Active · skips the mixer", row(pipelineGroups(input(r = opus.copy(offload = true), direct = 1)), "Android", "Direct path").detail)
    }

    @Test fun `buffer is worked out in milliseconds and frames`() {
        // 192,000 bytes of 32-bit float stereo at 48 kHz: 24,000 frames, half a second
        assertEquals("500 ms · 24,000 frames · AudioTrack", row(pipelineGroups(input()), "Android", "Buffer").detail)
    }

    @Test fun `skip silence and spatial sound appear only when on`() {
        assertFalse(pipelineGroups(input())[1].rows.any { it.title == "Skip silence" })
        val on = pipelineGroups(input().copy(skipSilence = true, spatial = true))[1]
        assertTrue(on.lit)
        assertTrue(on.rows.any { it.title == "Skip silence" } && on.rows.any { it.title == "Spatial sound" })
    }

    @Test fun `the verdict names the first thing in the way`() {
        assertEquals("Bit-perfect: Not guaranteed, the sound goes through the phone's mixer", pipelineVerdict(input()))
        assertEquals("Bit-perfect: Yes, the sound skips the phone's mixer", pipelineVerdict(input(r = opus.copy(offload = true))))
        assertEquals("Bit-perfect: No, the equalizer is on", pipelineVerdict(input(eqOn = true, leveling = true)))
        assertEquals("Bit-perfect: No, sound tuning is on", pipelineVerdict(input().copy(tuning = true)))
        assertEquals("Bit-perfect: No, the phone changes the sample rate", pipelineVerdict(input(r = opus.copy(outRate = 96_000), mixer = 48_000)))
        val bt = OutputInfo(OutputKind.Bluetooth, "Earbuds", null, null)
        assertEquals("Bit-perfect: No, Bluetooth sends compressed sound", pipelineVerdict(input(o = bt, eqOn = true)))
    }
}
