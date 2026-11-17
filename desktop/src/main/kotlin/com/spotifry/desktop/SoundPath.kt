package com.spotifry.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.LOCAL_PREFIX
import com.spotifry.music.data.Settings
import com.spotifry.music.player.AUTOEQ_PREFIX
import com.spotifry.music.player.Trim
import org.json.JSONObject
import kotlin.math.abs

/** A sound format as mpv reports it: [format] is mpv's name ("floatp", "s16", ...). */
data class AudioFormat(val rate: Int, val format: String, val channels: Int) {
    companion object {
        fun of(o: JSONObject?): AudioFormat? = o?.optInt("samplerate")?.takeIf { it > 0 }?.let { AudioFormat(it, o.optString("format"), o.optInt("channel-count")) }
    }
}

/** The playing song's path through mpv: where it came from, the file, what the decoder gives out, what mpv sends on, and to which sound system ([ao]). */
data class MpvSound(
    val origin: String? = null, val codec: String? = null, val bitrateBps: Double? = null,
    val decoded: AudioFormat? = null, val sent: AudioFormat? = null, val ao: String? = null,
    /** The song was on a slow server and went on from YouTube. */
    val fellBack: Boolean = false,
)

/** Where a song's sound comes from: a file on this computer, a saved copy, else the service [stream] names. */
internal fun originOf(id: String, local: Boolean, stream: String?): String? = when {
    id.startsWith(LOCAL_PREFIX) -> "local"
    local -> "saved"
    else -> stream
}

/** What the listener turned on, as the panel needs it. */
data class PathSettings(
    val eqOn: Boolean = false, val eqName: String? = null, val leveling: Boolean = false, val fadeSeconds: Int = 0,
    val speed: Float = 1f, val pitch: Int = 0, val trim: Trim? = null, val mono: Boolean = false,
    val skipSilence: Boolean = false, val spatial: Boolean = false, val soundSet: String? = null,
    /** Anything in the Tuning tab is changed. */
    val tuning: Boolean = false,
)

enum class PathIcon { Source, File, Decoder, SoundSet, Equalizer, Volume, Mono, Silence, Spatial, Server, Speakers, Headphones, Receives }

/** [note] is what the (i) opens; [hot]: this row changes the sound. */
data class PathRow(val icon: PathIcon, val title: String, val detail: String, val hot: Boolean = false, val note: String? = null)

/** [lit]: something in it changes the sound. [idle]: steps that are there but left alone, shown small. */
data class PathGroup(val name: String, val lit: Boolean, val rows: List<PathRow>, val idle: List<String> = emptyList())

private const val DASH = "—"

/** A format in plain words: "32-bit float", "16-bit". */
internal fun bitsText(mpvFormat: String): String? = when (mpvFormat.removeSuffix("p")) {
    "float" -> "32-bit float"; "double" -> "64-bit float"; "u8" -> "8-bit"; "s16" -> "16-bit"; "s24" -> "24-bit"; "s32" -> "32-bit"; "s64" -> "64-bit"
    else -> null
}

/** The server's own way of writing a format, "s32le 2ch 48000Hz", as its rate and bits. */
internal fun serverFormat(spec: String?): Pair<Int, String>? {
    val parts = spec?.split(' ') ?: return null
    val rate = parts.firstNotNullOfOrNull { p -> p.removeSuffix("Hz").takeIf { p.endsWith("Hz") }?.toIntOrNull() } ?: return null
    val f = parts.first().lowercase()
    val bits = when {
        f.startsWith("float32") -> "32-bit float"; f.startsWith("s16") -> "16-bit"; f.startsWith("s24") -> "24-bit"; f.startsWith("s32") -> "32-bit"; f.startsWith("u8") -> "8-bit"
        else -> return null
    }
    return rate to bits
}

/** 48000 becomes "48 kHz", 44100 "44.1 kHz". */
internal fun khz(hz: Int): String = (if (hz % 1000 == 0) "${hz / 1000}" else "%.1f".format(java.util.Locale.US, hz / 1000.0).trimEnd('0').trimEnd('.')) + " kHz"

private fun sourceName(origin: String?) = when (origin) {
    "monochrome" -> "Lossless server"; "saavn" -> "JioSaavn"; "youtube" -> "YouTube"
    "local" -> "A file on this computer"; "saved" -> "Saved on this computer"; else -> "Not known yet"
}

private fun codecName(codec: String?) = when (codec?.lowercase()) {
    null -> DASH; "opus" -> "Opus"; "aac" -> "AAC"; "flac" -> "FLAC"; "alac" -> "ALAC"; "mp3" -> "MP3"; "vorbis" -> "Vorbis"
    else -> codec.uppercase()
}

private fun serverName(ao: String?, out: SoundOutput?) = when {
    ao == "pipewire" || out?.server == "PipeWire" -> "PipeWire"
    ao == "pulse" -> "PulseAudio"
    ao == "alsa" -> "ALSA"
    else -> ao ?: "Sound server"
}

private fun trimText(t: Trim?): String? {
    if (t == null || t.isWhole) return null
    return listOfNotNull(t.startMs.takeIf { it > 0 }?.let { "Starts at ${clock(it)}" }, t.endMs?.let { "ends at ${clock(it)}" }).joinToString(" · ")
}
private fun speedText(speed: Float, pitch: Int): String {
    val s = "%.2f".format(java.util.Locale.US, speed).trimEnd('0').let { if (it.endsWith(".")) it + "0" else it } + "×"
    return if (pitch == 0) s else "$s · ${if (pitch > 0) "+" else "−"}${abs(pitch)} ${if (abs(pitch) == 1) "step" else "steps"}"
}

/** True when the sound server runs at another rate than mpv sends, so it resamples. Null when either isn't known. */
private fun rateChanged(m: MpvSound, out: SoundOutput?): Boolean? {
    val sent = m.sent?.rate ?: return null
    val runs = serverFormat(out?.spec)?.first ?: return null
    return sent != runs
}

/** The rows of the Sound path panel: only what mpv and the sound server really report. */
fun soundPathGroups(m: MpvSound, s: PathSettings, out: SoundOutput?, coming: String? = null): List<PathGroup> {
    val d = m.decoded
    val file = listOfNotNull(
        d?.rate?.let(::khz), d?.channels?.let { when (it) { 0 -> null; 1 -> "Mono"; 2 -> "Stereo"; else -> "$it channels" } },
        if (m.codec?.lowercase() in setOf("flac", "alac")) "Lossless" else m.bitrateBps?.takeIf { it > 0 }?.let { "${((it + 500) / 1000).toInt()} kbps" },
    ).joinToString(" · ")
    val input = PathGroup("Input", false, listOfNotNull(
        PathRow(PathIcon.Source, "Source", sourceName(m.origin) + if (m.fellBack) "\nThe better copy was too slow, so it went on from here" else "",
            note = "Where the song comes from: YouTube, the lossless server, JioSaavn, or a file on this computer."),
        coming?.let {
            PathRow(PathIcon.Source, "Next play", "${sourceName(it)} copy, downloading in the background",
                note = "A better copy than this one is fetched while you listen. The next time the song plays, it comes from that copy, with no waiting.")
        },
        PathRow(PathIcon.File, codecName(m.codec), if (m.codec == null) "Nothing playing yet" else file.ifEmpty { DASH }, note = "The file as it arrives, before anything touches it."),
        PathRow(PathIcon.Decoder, "Decoder", "FFmpeg, inside mpv" + (d?.format?.let(::bitsText)?.let { "\nGives out $it" } ?: ""), note = "The part of the player that turns the file into raw sound."),
    ))
    val trim = trimText(s.trim)
    val changing = buildList {
        s.soundSet?.let {
            add(PathRow(PathIcon.SoundSet, "Sound set", if (it == Settings.SPEAKER) "Speakers" else "Headphones",
                note = "Headphones and speakers each keep their own equalizer. Spotifry switches by itself when you change the output."))
        }
        add(PathRow(PathIcon.Equalizer, "Equalizer", if (!s.eqOn) "Off" else s.eqName?.removePrefix(AUTOEQ_PREFIX) ?: "Your own mix", hot = s.eqOn))
        add(PathRow(PathIcon.Volume, "Same volume for every song", if (s.leveling) "On" else "Off", hot = s.leveling, note = "Quiet songs are raised and loud ones lowered so you never reach for the volume."))
        if (s.fadeSeconds > 0) add(PathRow(PathIcon.Volume, "Fade between songs", "${s.fadeSeconds} s", hot = true))
        if (s.speed != 1f || s.pitch != 0) add(PathRow(PathIcon.Equalizer, "Speed and pitch", speedText(s.speed, s.pitch), hot = true))
        if (trim != null) add(PathRow(PathIcon.Decoder, "Start and end of the song", trim, hot = true))
        if (s.mono) add(PathRow(PathIcon.Mono, "Mono", "On", hot = true))
        if (s.skipSilence) add(PathRow(PathIcon.Silence, "Skip silence", "On", hot = true))
        if (s.spatial) add(PathRow(PathIcon.Spatial, "Spatial sound", "On", hot = true))
        if (s.tuning) add(PathRow(PathIcon.Equalizer, "Sound tuning", "On", hot = true, note = "Balance, width, gain, filters or night mode from the Equalizer & tuning page."))
    }
    // steps left alone are listed small, so the ones that change the sound stand out
    val idle = listOfNotNull("Fade · Off".takeIf { s.fadeSeconds == 0 }, "Speed · 1.0×".takeIf { s.speed == 1f && s.pitch == 0 }, "Whole song".takeIf { trim == null })
    val spotifry = PathGroup("Spotifry", changing.any { it.hot }, changing, idle)
    val changed = rateChanged(m, out)
    val runs = serverFormat(out?.spec)
    val sent = m.sent?.let { listOfNotNull(khz(it.rate), bitsText(it.format)).joinToString(" · ") } ?: DASH
    val server = PathGroup("Sound server", changed == true, listOf(
        PathRow(PathIcon.Server, serverName(m.ao, out),
            "$sent → ${runs?.let { "${khz(it.first)} · ${it.second}" } ?: DASH}" + when (changed) { true -> "\nRate changed by the computer"; false -> "\nNo change"; null -> "" },
            hot = changed == true,
            note = "Every app's sound is mixed here before it reaches your speakers. It only changes the sound when the rates differ; if it does, the computer did it, not Spotifry."),
    ))
    val output = PathGroup("Output", false, if (out == null) listOf(PathRow(PathIcon.Speakers, "Output", "Can't tell on this computer")) else listOf(
        PathRow(if (out.headphones) PathIcon.Headphones else PathIcon.Speakers, out.name, (if (out.headphones) "Headphones" else "Speakers") + if (out.bluetooth) " · Bluetooth" else ""),
        PathRow(PathIcon.Receives, "Receives", runs?.let { "${it.second} · ${khz(it.first)}" } ?: DASH, note = "What the speakers or headphones are given. It can differ from what plays."),
    ))
    return listOf(input, spotifry, server, output)
}

/** The first thing that keeps the sound from arriving exactly as decoded, or null when nothing in Spotifry or the server changes it. */
internal fun soundPathReason(m: MpvSound, s: PathSettings, out: SoundOutput?): String? = when {
    out?.bluetooth == true -> "Bluetooth sends compressed sound"
    s.eqOn -> "the equalizer is on"
    s.leveling -> "same volume for every song is on"
    s.fadeSeconds > 0 -> "fade between songs is on"
    s.speed != 1f || s.pitch != 0 -> "speed or pitch is changed"
    s.mono -> "mono is on"
    s.skipSilence -> "skip silence is on"
    s.spatial -> "spatial sound is on"
    s.tuning -> "sound tuning is on"
    rateChanged(m, out) == true -> "the sound server changes the sample rate"
    else -> null
}

/** One line on top: whether the sound reaches the output as the file decoded. Through a mixing server it is never certain. */
fun soundPathVerdict(m: MpvSound, s: PathSettings, out: SoundOutput?): String =
    soundPathReason(m, s, out)?.let { "No, $it" } ?: "Not guaranteed, the sound goes through the sound server's mixer"

private fun iconOf(i: PathIcon): ImageVector = when (i) {
    PathIcon.Source -> Icons.Download; PathIcon.File -> Icons.Doc; PathIcon.Decoder -> Icons.Cut; PathIcon.SoundSet -> Icons.Headphones
    PathIcon.Equalizer -> Icons.Tune; PathIcon.Volume -> Icons.Volume; PathIcon.Mono -> Icons.Volume; PathIcon.Silence -> Icons.Wave
    PathIcon.Spatial -> Icons.Sparkle; PathIcon.Server -> Icons.Computer; PathIcon.Speakers -> Icons.Volume; PathIcon.Headphones -> Icons.Headphones
    PathIcon.Receives -> Icons.Wave
}

/** The Sound path panel, in the queue's place on Now playing: every step from the song to the speakers, live. */
@Composable
fun SoundPathPanel(services: Services, width: Dp, onClose: () -> Unit, onEqualizer: () -> Unit, modifier: Modifier = Modifier) {
    val st = services.library.settings
    val m by services.sound.collectAsState()
    val out by services.output.collectAsState()
    val state by services.player.state.collectAsState()
    val eqOn by st.eqOn.collectAsState(); val eqName by st.eqPreset.collectAsState(); val leveling by st.sameLoudness.collectAsState()
    val fade by st.fadeSeconds.collectAsState(); val speed by st.speed.collectAsState(); val pitch by st.pitch.collectAsState()
    val mono by st.mono.collectAsState(); val silence by st.skipSilence.collectAsState(); val spatial by st.spatial.collectAsState()
    val perDevice by st.perDevice.collectAsState(); val set by st.soundSet.collectAsState()
    val tune by st.tune.collectAsState(); val compare by st.compare.collectAsState()
    // Compare takes the equalizer and the tuning out of what is heard
    val s = PathSettings(eqOn && !compare, eqName, leveling, fade, speed, pitch, state.trim, mono, silence, spatial != null, set.takeIf { perDevice }, !tune.neutral && !compare)
    val coming by services.betterComing.collectAsState()
    val groups = soundPathGroups(m, s, out, state.current?.id?.let(coming::get))
    val reason = soundPathReason(m, s, out)
    val rail = Palette.ink.copy(alpha = .14f)
    Column(modifier.width(width).fillMaxHeight().edgeLine().background(if (Palette.dark) Color.Black.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.45f)).padding(top = 14.dp, start = 24.dp, end = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sound path", fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = Palette.ink, modifier = Modifier.weight(1f))
            Tip("Close") { Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) { Text("×", fontSize = 22.sp, color = Palette.ink2) } }
        }
        Text("Every step from the song to your speakers, live.", fontSize = 13.5.sp, color = Palette.ink2, modifier = Modifier.padding(top = 2.dp, end = 12.dp))
        Column(Modifier.padding(top = 14.dp, end = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.surface).border(1.dp, Palette.line, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(color = Palette.ink2, fontWeight = FontWeight.SemiBold)); append("Bit-perfect: "); pop()
                append(soundPathVerdict(m, s, out))
            }, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp, color = Palette.ink)
            if (reason == "the equalizer is on") Text("Open equalizer ›", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.accent, modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onEqualizer))
        }
        Column(
            Modifier.padding(top = 12.dp).weight(1f).verticalScroll(rememberScrollState()).padding(end = 12.dp, bottom = 130.dp)
                // the line that joins every step, down the left
                .drawBehind { drawLine(rail, Offset(6.dp.toPx(), 10.dp.toPx()), Offset(6.dp.toPx(), size.height - 144.dp.toPx()), 2.dp.toPx()) },
        ) {
            groups.forEachIndexed { gi, g -> PathGroupView(g, if (gi == 0) 0.dp else 16.dp) }
        }
    }
}

@Composable
private fun PathGroupView(g: PathGroup, top: Dp) {
    val bg = Palette.bg
    Row(Modifier.padding(top = top).height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.size(12.dp).border(3.dp, bg, CircleShape).clip(CircleShape).background(if (g.lit) Palette.accent else Palette.ink.copy(alpha = .3f)))
        }
        Spacer(Modifier.width(6.dp))
        Text(g.name.uppercase(), fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .9.sp, color = Palette.ink)
        if (g.lit) Text("Changes the sound", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.accent, modifier = Modifier.padding(start = 10.dp))
        Box(Modifier.padding(start = 10.dp).weight(1f).height(1.dp).background(Palette.line))
    }
    g.rows.forEach { PathRowView(it) }
    if (g.idle.isNotEmpty()) @OptIn(ExperimentalLayoutApi::class) FlowRow(Modifier.padding(start = 24.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Left alone", fontSize = 12.sp, color = Palette.ink2, modifier = Modifier.align(Alignment.CenterVertically).padding(end = 2.dp))
        g.idle.forEach { Text(it, fontSize = 12.sp, color = Palette.ink2, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.ink.copy(alpha = .07f)).padding(horizontal = 9.dp, vertical = 3.dp)) }
    }
}

@Composable
private fun PathRowView(r: PathRow) {
    var open by remember(r.title) { mutableStateOf(false) }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(start = 3.dp, top = 7.dp).size(6.dp).clip(CircleShape).background(Palette.ink.copy(alpha = .3f)))
        Icon(iconOf(r.icon), null, Modifier.padding(start = 3.dp, top = 2.dp).size(20.dp), tint = if (r.hot) Palette.ink else Palette.ink2)
        Column(Modifier.weight(1f)) {
            Text(r.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp, color = Palette.ink)
            Text(r.detail, fontSize = 13.sp, lineHeight = 18.sp, color = if (r.hot) Palette.accent else Palette.ink2, fontWeight = if (r.hot) FontWeight.SemiBold else null)
        }
        if (r.note != null) Box(
            Modifier.size(20.dp).clip(CircleShape).border(1.5.dp, if (open) Palette.accent else Palette.ink.copy(alpha = .25f), CircleShape).clickable { open = !open },
            contentAlignment = Alignment.Center,
        ) { Text("i", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = if (open) Palette.accent else Palette.ink2) }
    }
    if (open && r.note != null) Text(
        r.note, fontSize = 12.5.sp, lineHeight = 18.sp, color = Palette.ink2,
        modifier = Modifier.padding(start = 56.dp, top = 6.dp, bottom = 2.dp).clip(RoundedCornerShape(12.dp)).background(Palette.high).padding(horizontal = 12.dp, vertical = 9.dp),
    )
}
