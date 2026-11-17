package com.spotifry.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray

/** Where the sound goes now: the default output of the sound server, as headphones or speakers. */
/** [spec] is the format the output runs at, as the server writes it: "s32le 2ch 48000Hz". */
data class SoundOutput(val headphones: Boolean, val name: String, val bluetooth: Boolean, val spec: String? = null, val server: String? = null)

private val HEADPHONE_WORDS = listOf("headphone", "headset", "hands-free", "handsfree", "earbud")

/**
 * Reads `pactl --format=json list sinks` for the sink called [default]. Headphones when its active port, its form factor or
 * its icon says so (a laptop's jack shows as a port of type Headphones), or when it is Bluetooth and not a speaker.
 * Null when that sink isn't in the list.
 */
internal fun soundOutputOf(sinksJson: String, default: String): SoundOutput? {
    val sinks = runCatching { JSONArray(sinksJson) }.getOrNull() ?: return null
    val sink = (0 until sinks.length()).map { sinks.getJSONObject(it) }.firstOrNull { it.optString("name") == default } ?: return null
    val props = sink.optJSONObject("properties")
    val active = sink.optString("active_port")
    val port = sink.optJSONArray("ports")?.let { ports -> (0 until ports.length()).map { ports.getJSONObject(it) }.firstOrNull { it.optString("name") == active } }
    val form = props?.optString("device.form_factor").orEmpty().lowercase()
    val bluetooth = props?.optString("device.api") == "bluez5" || default.startsWith("bluez_")
    fun says(text: String?) = text != null && HEADPHONE_WORDS.any { text.lowercase().contains(it) }
    val headphones = says(port?.optString("type")) || says(active) || says(port?.optString("description")) || says(form) ||
        says(props?.optString("device.icon_name")) || (bluetooth && form != "speaker" && form != "hifi" && form != "car")
    return SoundOutput(
        headphones, props?.optString("device.description")?.takeIf { it.isNotBlank() } ?: sink.optString("description"), bluetooth,
        sink.optString("sample_specification").takeIf { it.isNotBlank() }, sink.optString("driver").takeIf { it.isNotBlank() },
    )
}

/**
 * Follows the default output through `pactl` (PipeWire or PulseAudio): read once, then again whenever the server says a
 * sink or the default changed. [output] stays null when pactl isn't there or can't be read; the app then can't tell.
 */
class SoundOutputWatch(scope: CoroutineScope) {
    private val _output = MutableStateFlow<SoundOutput?>(null)
    val output: StateFlow<SoundOutput?> = _output

    private fun pactl(vararg args: String): String? = runCatching {
        val p = ProcessBuilder("pactl", *args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        p.inputStream.bufferedReader().readText().also { if (p.waitFor() != 0) return null }
    }.getOrNull()

    private fun read() {
        val default = pactl("get-default-sink")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val sinks = pactl("--format=json", "list", "sinks") ?: return
        soundOutputOf(sinks, default)?.let { _output.value = it }
    }

    init {
        // a volume drag sends a burst of changes; they are read once it settles
        val changed = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        if (Platform.find("pactl") != null) scope.launch(Dispatchers.IO) {
            read()
            launch { for (c in changed) { kotlinx.coroutines.delay(300); read() } }
            // ponytail: one `pactl subscribe` for the app's life; if it dies the output stays as last read, until the next start
            runCatching {
                val p = ProcessBuilder("pactl", "subscribe").redirectError(ProcessBuilder.Redirect.DISCARD).start()
                coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { p.destroy() }
                p.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isActive) { p.destroy(); break }
                        if ("on sink #" in line || "on server" in line || "on card" in line) changed.trySend(Unit)
                    }
                }
            }
        }
    }
}
