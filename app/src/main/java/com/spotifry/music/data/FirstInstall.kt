package com.spotifry.music.data

/** True when this install has never been updated, which is how a fresh install looks to the package manager. */
fun neverUpdated(context: android.content.Context): Boolean = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.firstInstallTime == info.lastUpdateTime
} catch (e: Exception) { false }
