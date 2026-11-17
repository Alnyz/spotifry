package com.spotifry.music.player

import androidx.media3.common.C
import kotlin.math.abs

/** The picture beside a row of the Audio pipeline sheet. */
enum class PipelineIcon { Source, File, Decoder, Format, Equalizer, Volume, Fade, Speed, Trim, Silence, Spatial, Mixer, Direct, Buffer, Speaker, Bluetooth, Usb, Headphones, Receives, Supports }

/** [note] is the plain-words explanation the (i) opens; null rows have no (i). [hot]: this row changes the sound. */
data class PipelineRow(val icon: PipelineIcon, val title: String, val detail: String, val hot: Boolean = false, val note: String? = null)

/** [lit]: something in the group changes the sound, so its dot is the accent colour. */
data class PipelineGroup(val name: String, val lit: Boolean, val rows: List<PipelineRow>)

enum class OutputKind { Phone, Bluetooth, Usb, Wired }

/** The Bluetooth codec in use, as Android reports it; any part can be missing. */
data class BtCodec(val name: String, val rateHz: Int?, val bits: Int?)

/** Where the sound goes. [maxBits] and [maxRateHz] are what the device says it can take; null when it does not say. */
data class OutputInfo(val kind: OutputKind, val name: String, val maxBits: Int?, val maxRateHz: Int?, val codec: BtCodec? = null)

/** Everything the sheet shows, gathered. [directSupport] is `AudioManager.getDirectPlaybackSupport` (Android 13 and up), null before. */
data class PipelineInput(
    val origin: String?, val readings: AudioReadings, val bitrateKbps: Int?,
    val eqOn: Boolean, val eqName: String?, val leveling: Boolean, val gainDb: Float?, val fadeSeconds: Int, val speed: Float, val pitch: Int,
    val trimText: String?, val skipSilence: Boolean, val spatial: Boolean,
    val mixerRateHz: Int?, val directSupport: Int?, val output: OutputInfo,
    /** Anything in the equalizer page's Tuning tab is changed. */
    val tuning: Boolean = false,
)

private const val DASH = "—"

fun pipelineGroups(i: PipelineInput): List<PipelineGroup> {
    val r = i.readings
    val input = PipelineGroup("Input", false, listOf(
        PipelineRow(PipelineIcon.Source, "Source", sourceName(i.origin), note = "Where the song comes from: YouTube, the lossless server, JioSaavn, or a file on this phone."),
        PipelineRow(PipelineIcon.File, codecName(r.mime), formatDetail(r, i.bitrateKbps), note = "The file as it arrives, before anything touches it."),
        PipelineRow(PipelineIcon.Decoder, "Decoder", decoderDetail(r), note = "The part of Android that turns the file into raw sound."),
    ))
    val on = i.eqOn || i.leveling || i.fadeSeconds > 0 || i.speed != 1f || i.pitch != 0 || i.trimText != null || i.skipSilence || i.spatial || i.tuning
    val spotifry = PipelineGroup("Spotifry", on, buildList {
        add(PipelineRow(PipelineIcon.Format, "Processing format", r.outEncoding?.let(::encodingText) ?: DASH, note = "Spotifry works on the sound in 32-bit float when it can, so the equalizer and volume have room and never clip inside."))
        add(PipelineRow(PipelineIcon.Equalizer, "Equalizer", if (!i.eqOn) "Off" else i.eqName?.removePrefix(AUTOEQ_PREFIX) ?: "Your own mix", hot = i.eqOn))
        add(PipelineRow(PipelineIcon.Volume, "Same volume for every song", if (!i.leveling) "Off" else "On" + (i.gainDb?.let { " · " + signedDb(it) } ?: ""), hot = i.leveling,
            note = "Quiet songs are raised and loud ones lowered so you never reach for the volume."))
        add(PipelineRow(PipelineIcon.Fade, "Fade between songs", if (i.fadeSeconds > 0) "${i.fadeSeconds} s" else "Off", hot = i.fadeSeconds > 0))
        add(PipelineRow(PipelineIcon.Speed, "Speed and pitch", speedText(i.speed, i.pitch), hot = i.speed != 1f || i.pitch != 0))
        add(PipelineRow(PipelineIcon.Trim, "Start and end of the song", i.trimText ?: "Whole song", hot = i.trimText != null))
        // not in every list: only when they are on, since then they change the sound
        if (i.skipSilence) add(PipelineRow(PipelineIcon.Silence, "Skip silence", "On", hot = true))
        if (i.spatial) add(PipelineRow(PipelineIcon.Spatial, "Spatial sound", "On", hot = true))
        if (i.tuning) add(PipelineRow(PipelineIcon.Equalizer, "Sound tuning", "On", hot = true, note = "Balance, width, gain, filters or night mode from the Equalizer & tuning page."))
    })
    val rate = r.outRate
    val changed = rate != null && i.mixerRateHz != null && rate != i.mixerRateHz
    val android = PipelineGroup("Android", changed, listOf(
        PipelineRow(PipelineIcon.Mixer, "Phone mixer",
            if (rate == null || i.mixerRateHz == null) DASH else "${hzNumber(rate)} → ${hzText(i.mixerRateHz)}" + if (changed) "" else ", no change",
            hot = changed, note = "Android's own mixer sits between every app and the speaker. It only changes the sound when the rates differ; if it does, Android did it, not Spotifry."),
        PipelineRow(PipelineIcon.Direct, "Direct path", directText(i.directSupport, r.offload),
            note = "Direct means the sound goes straight to the device and skips the phone's mixer. Mixed means it shares the mixer with other apps."),
        PipelineRow(PipelineIcon.Buffer, "Buffer", bufferText(r), note = "How much sound waits in line before it is played. AudioTrack is the Android part that plays it."),
    ))
    val o = i.output
    val output = PipelineGroup("Output", false, listOf(
        PipelineRow(when (o.kind) { OutputKind.Phone -> PipelineIcon.Speaker; OutputKind.Bluetooth -> PipelineIcon.Bluetooth; OutputKind.Usb -> PipelineIcon.Usb; OutputKind.Wired -> PipelineIcon.Headphones },
            o.name, when (o.kind) { OutputKind.Phone -> "Built in"; OutputKind.Bluetooth -> "Bluetooth" + (o.codec?.let { " · " + it.name } ?: " · codec set by your phone"); OutputKind.Usb -> "Wired, USB"; OutputKind.Wired -> "Wired" }),
        PipelineRow(PipelineIcon.Receives, "Receives", receivesText(r), note = "What the earphones or speaker are given. It can differ from what plays."),
        PipelineRow(PipelineIcon.Supports, "Device supports", supportsText(o), note = "What the device says it can take. It tells what the phone could send, not what is playing."),
    ))
    return listOf(input, spotifry, android, output)
}

/**
 * One line at the top of the sheet: whether the sound reaches the output exactly as the file decoded, and if not, the
 * first thing in the way. Honest about what Android hides: through the phone's mixer it is "not guaranteed".
 */
fun pipelineVerdict(i: PipelineInput): String {
    val reason = when {
        i.output.kind == OutputKind.Bluetooth -> "Bluetooth sends compressed sound"
        i.eqOn -> "the equalizer is on"
        i.leveling -> "same volume for every song is on"
        i.fadeSeconds > 0 -> "fade between songs is on"
        i.speed != 1f || i.pitch != 0 -> "speed or pitch is changed"
        i.skipSilence -> "skip silence is on"
        i.spatial -> "spatial sound is on"
        i.tuning -> "sound tuning is on"
        i.readings.outRate != null && i.mixerRateHz != null && i.readings.outRate != i.mixerRateHz -> "the phone changes the sample rate"
        else -> null
    }
    if (reason != null) return "Bit-perfect: No, $reason"
    val direct = i.readings.offload
    return if (direct) "Bit-perfect: Yes, the sound skips the phone's mixer" else "Bit-perfect: Not guaranteed, the sound goes through the phone's mixer"
}

internal fun sourceName(origin: String?) = when (origin) {
    "monochrome" -> "Lossless server"; "saavn" -> "JioSaavn"; "youtube" -> "YouTube"
    "local" -> "A file on this phone"; "saved" -> "Saved on this phone"; else -> "Not known yet"
}

/**
 * Where the song playing comes from, for [sourceName]: a file on the phone, a saved copy, else the service its cached bytes
 * ([cached]) or its link ([known]) came from. Null when none of that is known yet: a song still starting isn't on the phone.
 */
fun songSource(id: String, saved: Boolean, cached: String?, known: String?): String? = when {
    id.startsWith(com.spotifry.music.data.LOCAL_PREFIX) -> "local"
    saved -> "saved"
    else -> cached ?: known
}

internal fun codecName(mime: String?): String = when (mime) {
    null -> DASH
    "audio/opus" -> "Opus"; "audio/mp4a-latm" -> "AAC"; "audio/flac" -> "FLAC"; "audio/mpeg" -> "MP3"; "audio/vorbis" -> "Vorbis"; "audio/raw" -> "PCM"
    else -> mime.substringAfter('/').uppercase()
}

private fun formatDetail(r: AudioReadings, kbps: Int?): String {
    if (r.mime == null) return "Nothing playing yet"
    val rate = r.sampleRate?.let(::hzText)
    val ch = r.channels?.let { when (it) { 1 -> "Mono"; 2 -> "Stereo"; else -> "$it channels" } }
    val rateBits = if (r.mime == "audio/flac") "Lossless" else (r.bitrateKbps ?: kbps)?.let { "$it kbps" }
    return listOfNotNull(rate, ch, rateBits).joinToString(" · ").ifEmpty { DASH }
}

private fun decoderDetail(r: AudioReadings): String {
    val name = r.decoder?.let { it + if (it.startsWith("c2.android.") || it.startsWith("OMX.google.")) " · software" else "" } ?: DASH
    val bits = r.decodedEncoding?.let(::bitsOf)
    val rate = r.sampleRate; val ch = r.channels
    val out = if (bits == null) null else "Gives out ${bits}-bit PCM" + if (rate != null && ch != null) " · ${"%,d".format(rate.toLong() * bits * ch / 1000)} kbps" else ""
    return listOfNotNull(name, out).joinToString("\n")
}

internal fun bitsOf(encoding: Int): Int? = when (encoding) {
    C.ENCODING_PCM_8BIT -> 8; C.ENCODING_PCM_16BIT -> 16; C.ENCODING_PCM_24BIT -> 24; C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32; else -> null
}

internal fun encodingText(encoding: Int): String =
    if (encoding == C.ENCODING_PCM_FLOAT) "32-bit float PCM" else bitsOf(encoding)?.let { "$it-bit PCM" } ?: DASH

private fun receivesText(r: AudioReadings): String {
    val enc = r.outEncoding ?: return DASH
    val bits = if (enc == C.ENCODING_PCM_FLOAT) "32-bit float" else bitsOf(enc)?.let { "$it-bit" } ?: return DASH
    return listOfNotNull(bits, r.outRate?.let(::hzText)).joinToString(" · ")
}

private fun supportsText(o: OutputInfo): String {
    o.codec?.let { c -> return listOfNotNull(c.name, c.rateHz?.let(::hzText), c.bits?.let { "$it-bit" }).joinToString(" · ") }
    val bits = o.maxBits?.let { "$it-bit" }; val rate = o.maxRateHz?.let(::hzText)
    if (bits == null && rate == null) return if (o.kind == OutputKind.Bluetooth) "Set by your phone" else DASH
    return "Up to " + listOfNotNull(bits, rate).joinToString(" · ")
}

private fun bufferText(r: AudioReadings): String {
    val bytes = r.outBufferBytes; val enc = r.outEncoding; val rate = r.outRate
    val width = enc?.let(::bitsOf)?.div(8)
    val channels = r.outChannelMask?.let { Integer.bitCount(it) }?.takeIf { it > 0 } ?: r.channels
    if (bytes == null || width == null || rate == null || channels == null) return "AudioTrack"
    val frames = bytes / (width * channels)
    return "${"%,d".format(frames.toLong() * 1000 / rate)} ms · ${"%,d".format(frames)} frames · AudioTrack"
}

/** [support]: the bits of `getDirectPlaybackSupport`, 0 for none; null when this Android is too old to say. */
private fun directText(support: Int?, offloaded: Boolean) = when {
    offloaded -> "Active · skips the mixer"
    support == null -> DASH
    support == 0 -> "Not supported · mixed path"
    else -> "Available, not in use"
}

private fun speedText(speed: Float, pitch: Int): String {
    val s = "${trimZero(speed)}×"
    return if (pitch == 0) s else "$s · ${if (pitch > 0) "+" else "−"}${abs(pitch)} ${if (abs(pitch) == 1) "step" else "steps"}"
}

private fun trimZero(x: Float): String = "%.2f".format(java.util.Locale.US, x).trimEnd('0').let { if (it.endsWith(".")) it + "0" else it }

private fun signedDb(db: Float) = (if (db < 0) "−" else "+") + "%.1f dB".format(java.util.Locale.US, abs(db))

/** 48000 becomes "48 kHz", 44100 "44.1 kHz". */
internal fun hzText(hz: Int): String = hzNumber(hz) + " kHz"
internal fun hzNumber(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000}" else "%.1f".format(java.util.Locale.US, hz / 1000.0).trimEnd('0').trimEnd('.')
