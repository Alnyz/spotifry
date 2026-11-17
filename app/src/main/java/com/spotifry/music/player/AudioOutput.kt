package com.spotifry.music.player

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Where the sound goes now: the first of Bluetooth, USB, wired headphones that is connected, else the phone's speaker. [codec] comes from [bluetoothCodec]. */
fun outputInfo(context: Context, codec: BtCodec? = null): OutputInfo {
    val devices = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    fun named(d: AudioDeviceInfo, fallback: String) = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: fallback
    fun info(d: AudioDeviceInfo, kind: OutputKind, fallback: String) = OutputInfo(kind, named(d, fallback), maxBits(d), d.sampleRates.maxOrNull()?.takeIf { it > 0 }, if (kind == OutputKind.Bluetooth) codec else null)
    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER }?.let { return info(it, OutputKind.Bluetooth, "Bluetooth") }
    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }?.let { return info(it, OutputKind.Usb, "USB") }
    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }?.let { return info(it, OutputKind.Wired, "Headphones") }
    return OutputInfo(OutputKind.Phone, "Phone speaker", null, null)
}

/** The most bits the device says it takes; null when it does not list encodings. */
private fun maxBits(d: AudioDeviceInfo): Int? = d.encodings.toList().mapNotNull {
    when (it) { AudioFormat.ENCODING_PCM_16BIT -> 16; AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24; AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32; else -> null }
}.maxOrNull()

/** The rate Android's mixer runs at (what it brings every app's sound to). Null when the phone does not say. */
fun mixerRateHz(context: Context): Int? =
    (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()

/** `getDirectPlaybackSupport` for the sound being sent (0: none). Null before Android 13, which cannot say, or before the player has said what it sends. */
fun directSupport(context: Context, r: AudioReadings): Int? {
    if (Build.VERSION.SDK_INT < 33) return null
    val enc = r.outEncoding ?: return null
    val rate = r.outRate ?: return null
    val mask = r.outChannelMask ?: return null
    return try {
        val format = AudioFormat.Builder().setEncoding(enc).setSampleRate(rate).setChannelMask(mask).build()
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        AudioManager.getDirectPlaybackSupport(format, attributes)
    } catch (e: Exception) { null }
}

/** Android 12 and up ask for "Nearby devices" before an app may talk to a Bluetooth device. */
fun needsBluetoothPermission(context: Context) =
    Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED

/**
 * The codec the headphones are using (AAC, LDAC, aptX...), with its rate and bit depth. Android keeps this out of its
 * public interface on some versions, so it is read through reflection and any refusal gives null; the sheet then
 * shows "Bluetooth" alone. Needs the Nearby devices permission. Gives up after 2 seconds.
 */
@android.annotation.SuppressLint("MissingPermission")
suspend fun bluetoothCodec(context: Context): BtCodec? {
    if (needsBluetoothPermission(context)) return null
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
    return withTimeoutOrNull(2_000) {
        suspendCancellableCoroutine { cont ->
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val codec = runCatching { readCodec(proxy as BluetoothA2dp) }.getOrNull()
                    runCatching { adapter.closeProfileProxy(profile, proxy) }
                    if (cont.isActive) cont.resume(codec)
                }
                override fun onServiceDisconnected(profile: Int) {}
            }
            val asked = runCatching { adapter.getProfileProxy(context.applicationContext, listener, BluetoothProfile.A2DP) }.getOrDefault(false)
            if (!asked && cont.isActive) cont.resume(null)
        }
    }
}

@android.annotation.SuppressLint("MissingPermission")
private fun readCodec(a2dp: BluetoothA2dp): BtCodec? {
    val device = a2dp.connectedDevices.firstOrNull() ?: return null
    val status = a2dp.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java).invoke(a2dp, device) ?: return null
    val config = status.javaClass.getMethod("getCodecConfig").invoke(status) ?: return null
    fun call(name: String) = runCatching { config.javaClass.getMethod(name).invoke(config) }.getOrNull()
    val name = (call("getCodecName") as? String) ?: codecTypeName(call("getCodecType") as? Int) ?: return null
    // rate and depth come as bit flags: the lowest set bit is the one in use
    val rate = (call("getSampleRate") as? Int)?.let { mapOf(1 to 44_100, 2 to 48_000, 4 to 88_200, 8 to 96_000, 16 to 176_400, 32 to 192_000)[it] }
    val bits = (call("getBitsPerSample") as? Int)?.let { mapOf(1 to 16, 2 to 24, 4 to 32)[it] }
    return BtCodec(name, rate, bits)
}

private fun codecTypeName(type: Int?) = when (type) { 0 -> "SBC"; 1 -> "AAC"; 2 -> "aptX"; 3 -> "aptX HD"; 4 -> "LDAC"; else -> null }

/** Which sound set an output uses: the phone's own speaker, or headphones for anything plugged in or connected. */
fun soundSetFor(kind: OutputKind): String = if (kind == OutputKind.Phone) com.spotifry.music.data.Settings.SPEAKER else com.spotifry.music.data.Settings.HEADPHONES
