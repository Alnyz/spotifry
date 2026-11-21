package com.spotifry.music.player

import android.bluetooth.BluetoothClass.Device
import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class OutputLookTest {
    private fun bt(name: String, cls: Int? = null) = outputLook(OutputKind.Bluetooth, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, cls, name)

    @Test fun thePhoneAndBluetoothSpeakersAreSpeakers() {
        assertEquals(OutputLook.Speaker, outputLook(OutputKind.Phone, null, null, "Phone speaker"))
        assertEquals(OutputLook.Speaker, outputLook(OutputKind.Bluetooth, AudioDeviceInfo.TYPE_BLE_SPEAKER, null, "Kitchen"))
        assertEquals(OutputLook.Speaker, bt("JBL Flip 6", Device.AUDIO_VIDEO_LOUDSPEAKER))
        assertEquals(OutputLook.Speaker, bt("Boombox", Device.AUDIO_VIDEO_PORTABLE_AUDIO))
    }

    @Test fun theBluetoothClassWinsOverTheName() {
        assertEquals(OutputLook.Headphones, bt("Galaxy Buds", Device.AUDIO_VIDEO_HEADPHONES))
        assertEquals(OutputLook.Earbuds, bt("WH-1000XM5", Device.AUDIO_VIDEO_WEARABLE_HEADSET))
        assertEquals(OutputLook.Earbuds, bt("Car kit", Device.AUDIO_VIDEO_HANDSFREE))
    }

    @Test fun withoutAClassTheNameDecides() {
        listOf("Galaxy Buds2 Pro", "AirPods Pro", "TWS-X9", "Soundcore Earphones", "Sony in-ear").forEach { assertEquals(it, OutputLook.Earbuds, bt(it)) }
        listOf("WH-1000XM5", "Bose over-ear", "Headphones", "Mystery device").forEach { assertEquals(it, OutputLook.Headphones, bt(it)) }
    }

    @Test fun wiredWithAMicIsEarphonesWithoutIsHeadphonesAndUsbIsHeadphones() {
        assertEquals(OutputLook.Earbuds, outputLook(OutputKind.Wired, AudioDeviceInfo.TYPE_WIRED_HEADSET, null, "Headset"))
        assertEquals(OutputLook.Headphones, outputLook(OutputKind.Wired, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, null, "Headphones"))
        assertEquals(OutputLook.Headphones, outputLook(OutputKind.Usb, AudioDeviceInfo.TYPE_USB_HEADSET, null, "USB-C DAC"))
    }
}
