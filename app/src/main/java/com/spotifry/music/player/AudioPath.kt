package com.spotifry.music.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** One step of the sound's way to the listener, in plain words. */
data class AudioPathRow(val label: String, val value: String)

/**
 * The steps from the file to the speaker, as the Audio path sheet lists them. [origin] is the service the song came from
 * (null when it plays from a file on the phone); [quality] is the label under the cover; [eqName] is the equalizer's
 * ready-made sound, null for the listener's own mix.
 */
fun audioPathRows(quality: String?, origin: String?, leveling: Boolean, eqOn: Boolean, eqName: String?, output: String): List<AudioPathRow> {
    val from = when (origin) { "monochrome" -> "Lossless server"; "saavn" -> "JioSaavn"; "youtube" -> "YouTube"; else -> "On this phone" }
    return listOf(
        AudioPathRow("Source", listOfNotNull(from, quality).joinToString(" · ")),
        AudioPathRow("Same volume for every song", if (leveling) "On" else "Off"),
        AudioPathRow("Equalizer", if (!eqOn) "Off" else eqName?.removePrefix(AUTOEQ_PREFIX) ?: "Your own mix"),
        AudioPathRow("Output", output),
    )
}

/** Where the sound goes now: the first of Bluetooth, USB, wired headphones that is connected, else the phone's speaker. */
fun outputName(context: Context): String {
    val devices = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    fun named(d: AudioDeviceInfo, fallback: String) = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: fallback
    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER }?.let { return named(it, "Bluetooth") }
    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }?.let { return named(it, "USB") }
    if (devices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }) return "Headphones"
    return "Phone speaker"
}
