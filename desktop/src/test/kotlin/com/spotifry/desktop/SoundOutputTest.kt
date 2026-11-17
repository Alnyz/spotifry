package com.spotifry.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundOutputTest {
    private fun sink(name: String, port: String?, type: String?, props: String) =
        """{"name":"$name","description":"D $name","active_port":${port?.let { "\"$it\"" } ?: "null"},"ports":[${port?.let { """{"name":"$it","description":"$it","type":"$type"}""" } ?: ""}],"properties":{$props}}"""
    private fun out(vararg sinks: String, default: String) = soundOutputOf("[${sinks.joinToString(",")}]", default)

    @Test fun aLaptopSpeakerIsSpeakers() {
        val o = out(sink("alsa.Speaker", "[Out] Speaker", "Speaker", """"device.icon_name":"audio-speakers","device.description":"Built-in Audio""""), default = "alsa.Speaker")
        assertEquals(SoundOutput(false, "Built-in Audio", false), o)
    }

    @Test fun theHeadphoneJackIsHeadphonesWhetherByPortTypeOrPortName() {
        assertEquals(true, out(sink("alsa.Headphones", "[Out] Headphones", "Headphones", ""), default = "alsa.Headphones")?.headphones)
        assertEquals(true, out(sink("alsa.analog", "analog-output-headphones", "Unknown", ""), default = "alsa.analog")?.headphones)
        assertEquals(false, out(sink("alsa.analog", "analog-output-speaker", "Unknown", ""), default = "alsa.analog")?.headphones)
    }

    @Test fun bluetoothIsHeadphonesUnlessItIsASpeaker() {
        val buds = out(sink("bluez_output.AA", null, null, """"device.api":"bluez5","device.form_factor":"headset""""), default = "bluez_output.AA")
        assertEquals(true, buds?.headphones); assertEquals(true, buds?.bluetooth)
        assertEquals(false, out(sink("bluez_output.BB", null, null, """"device.api":"bluez5","device.form_factor":"speaker""""), default = "bluez_output.BB")?.headphones)
    }

    @Test fun onlyTheDefaultSinkCountsAndAMissingOneIsUnknown() {
        val a = sink("alsa.Speaker", "[Out] Speaker", "Speaker", ""); val b = sink("usb.Headset", null, null, """"device.form_factor":"headset"""")
        assertEquals(true, out(a, b, default = "usb.Headset")?.headphones)
        assertNull(out(a, default = "gone"))
        assertNull(soundOutputOf("not json", "x"))
    }
}
