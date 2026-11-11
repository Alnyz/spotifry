package com.spotifry.music.player

import android.content.SharedPreferences
import com.spotifry.music.data.asPrefs
import okhttp3.OkHttpClient

fun Trims(prefs: SharedPreferences) = Trims(prefs.asPrefs())
fun SmartTrims(prefs: SharedPreferences, client: OkHttpClient) = SmartTrims(prefs.asPrefs(), client)
