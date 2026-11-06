package com.spotifry.music.player

import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings

private fun volumePanel() = Intent(Settings.Panel.ACTION_VOLUME)

/**
 * Whether this phone has a screen for choosing where the sound goes. Android 14 and later have the system output
 * switcher. Android 13 has none for apps, but its volume panel carries the "play media to" choice, so that opens instead.
 */
fun hasOutputPicker(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE || volumePanel().resolveActivity(context.packageManager) != null

/** Opens the system's own output picker (speaker, Bluetooth, USB). Returns false when there is none to open. */
fun showOutputPicker(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && MediaRouter2.getInstance(context).showSystemOutputSwitcher()) return true
    return runCatching { context.startActivity(volumePanel()) }.isSuccess
}
