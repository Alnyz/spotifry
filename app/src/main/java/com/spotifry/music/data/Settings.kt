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
    private val _skipSilence = MutableStateFlow(prefs.getBoolean(SKIP_SILENCE, false))
    /** Silence inside songs is jumped over. */
    val skipSilence: StateFlow<Boolean> = _skipSilence
    fun setSkipSilence(on: Boolean) { _skipSilence.value = on; prefs.edit().putBoolean(SKIP_SILENCE, on).apply() }

    private val _speed = MutableStateFlow(prefs.getFloat(SPEED, 1f))
    /** Playback speed, 0.5 to 2. Kept for every song until set back. */
    val speed: StateFlow<Float> = _speed
    fun setSpeed(x: Float) { _speed.value = x; prefs.edit().putFloat(SPEED, x).apply() }

    private val _pitch = MutableStateFlow(prefs.getInt(PITCH, 0))
    /** Pitch in semitones, -6 to +6, separate from speed. */
    val pitch: StateFlow<Int> = _pitch
    fun setPitch(steps: Int) { _pitch.value = steps; prefs.edit().putInt(PITCH, steps).apply() }

    private val _fadeSeconds = MutableStateFlow(prefs.getInt(FADE, 0))
    /** Seconds the end of a song fades out and the next fades in, when one runs into the next. 0 is off. */
    val fadeSeconds: StateFlow<Int> = _fadeSeconds
    fun setFadeSeconds(s: Int) { _fadeSeconds.value = s; prefs.edit().putInt(FADE, s).apply() }

    private val _eqOn = MutableStateFlow(prefs.getBoolean(EQ_ON, false))
    val eqOn: StateFlow<Boolean> = _eqOn
    private val _eqPreset = MutableStateFlow(prefs.getString(EQ_PRESET, null))
    /** Name of the phone's ready-made sound in use, or null for the user's own mix. */
    val eqPreset: StateFlow<String?> = _eqPreset
    private val _eqLevels = MutableStateFlow(prefs.getString(EQ_LEVELS, "").orEmpty().split(',').mapNotNull { it.toIntOrNull() })
    /** Level of each equalizer bar in millibels (100 = 1 dB), low to high. Empty until first set. */
    val eqLevels: StateFlow<List<Int>> = _eqLevels
    fun setEqOn(on: Boolean) { _eqOn.value = on; prefs.edit().putBoolean(EQ_ON, on).apply() }
    fun setEq(preset: String?, levels: List<Int>) {
        _eqPreset.value = preset; _eqLevels.value = levels
        prefs.edit().putString(EQ_PRESET, preset).putString(EQ_LEVELS, levels.joinToString(",")).apply()
    }

    private val _accent = MutableStateFlow(prefs.getString(ACCENT, null) ?: "EMBER")
    /** Name of the accent choice: a ready colour, PHONE (from the wallpaper) or CUSTOM ([accentColor]). */
    val accent: StateFlow<String> = _accent
    private val _accentColor = MutableStateFlow(prefs.getInt(ACCENT_COLOR, 0xFFFF8A3D.toInt()))
    /** The colour picked by hand (ARGB), used when [accent] is CUSTOM. */
    val accentColor: StateFlow<Int> = _accentColor
    fun setAccent(choice: String, color: Int) {
        _accent.value = choice; _accentColor.value = color
        prefs.edit().putString(ACCENT, choice).putInt(ACCENT_COLOR, color).apply()
    }

    private val _pureBlack = MutableStateFlow(prefs.getBoolean(PURE_BLACK, false))
    /** The dark theme in true black instead of dark grey. */
    val pureBlack: StateFlow<Boolean> = _pureBlack
    fun setPureBlack(on: Boolean) { _pureBlack.value = on; prefs.edit().putBoolean(PURE_BLACK, on).apply() }

    fun setSameLoudness(on: Boolean) { _sameLoudness.value = on; prefs.edit().putBoolean(SAME_LOUDNESS, on).apply() }

    fun setDataSaver(on: Boolean) { _dataSaver.value = on; prefs.edit().putBoolean(DATA_SAVER, on).apply() }
    fun setSaveLow(on: Boolean) { _saveLow.value = on; prefs.edit().putBoolean(SAVE_LOW, on).apply() }
    fun setTheme(mode: ThemeMode) { _theme.value = mode; prefs.edit().putString(THEME, mode.name).apply() }

    private companion object {
        const val DATA_SAVER = "data_saver"
        const val SAVE_LOW = "save_low"
        const val THEME = "theme"
        const val SAME_LOUDNESS = "same_loudness"
        const val SKIP_SILENCE = "skip_silence"
        const val SPEED = "speed"
        const val PITCH = "pitch"
        const val FADE = "fade_seconds"
        const val EQ_ON = "eq_on"
        const val EQ_PRESET = "eq_preset"
        const val EQ_LEVELS = "eq_levels"
        const val ACCENT = "accent"
        const val PURE_BLACK = "pure_black"
        const val ACCENT_COLOR = "accent_color"
    }
}

/** What the pill and menu show, or null when speed and pitch are both normal: "1.25×", "+2", "0.75× · −1". */
fun speedPitchLabel(speed: Float, pitch: Int): String? {
    val parts = buildList {
        if (kotlin.math.abs(speed - 1f) > .001f) add("%.2f".format(java.util.Locale.US, speed).trimEnd('0').trimEnd('.') + "×")
        if (pitch != 0) add(if (pitch > 0) "+$pitch" else "−${-pitch}")
    }
    return parts.joinToString(" · ").ifEmpty { null }
}
