package com.spotifry.music.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { PHONE, LIGHT, DARK }

/** The few choices on the Settings page, kept on the phone. Each change is saved at once. */
class Settings(private val prefs: SharedPreferences) {
    private val _dataSaver = MutableStateFlow(prefs.getBoolean(DATA_SAVER, false))
    private val _saveLow = MutableStateFlow(prefs.getBoolean(SAVE_LOW, false))
    private val _theme = MutableStateFlow(runCatching { ThemeMode.valueOf(prefs.getString(THEME, null)!!) }.getOrDefault(ThemeMode.PHONE))

    /** Lowest quality while on mobile data; Wi-Fi always plays high. */
    val dataSaver: StateFlow<Boolean> = _dataSaver
    /** Songs saved to the phone in low quality instead of high. */
    val saveLow: StateFlow<Boolean> = _saveLow
    val theme: StateFlow<ThemeMode> = _theme
    private val _sameLoudness = MutableStateFlow(prefs.getBoolean(SAME_LOUDNESS, true))
    /** Quiet songs turned up, loud ones down. On unless turned off. */
    val sameLoudness: StateFlow<Boolean> = _sameLoudness
    fun setSameLoudness(on: Boolean) { _sameLoudness.value = on; prefs.edit().putBoolean(SAME_LOUDNESS, on).apply() }

    fun setDataSaver(on: Boolean) { _dataSaver.value = on; prefs.edit().putBoolean(DATA_SAVER, on).apply() }
    fun setSaveLow(on: Boolean) { _saveLow.value = on; prefs.edit().putBoolean(SAVE_LOW, on).apply() }
    fun setTheme(mode: ThemeMode) { _theme.value = mode; prefs.edit().putString(THEME, mode.name).apply() }

    private companion object {
        const val DATA_SAVER = "data_saver"
        const val SAVE_LOW = "save_low"
        const val THEME = "theme"
        const val SAME_LOUDNESS = "same_loudness"
    }
}
