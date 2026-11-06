package com.spotifry.music.data

import android.content.SharedPreferences
import com.spotifry.music.player.EQ_BANDS
import com.spotifry.music.player.EQ_PRESETS
import com.spotifry.music.player.AUTOEQ_PREFIX
import com.spotifry.music.player.eqFromOldBands
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
    private val _keepScreenOn = MutableStateFlow(prefs.getBoolean(KEEP_SCREEN_ON, false))
    /** The screen stays on while Now playing is open and a song plays. Off until chosen in the song menu. */
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn
    private val _sameLoudness = MutableStateFlow(prefs.getBoolean(SAME_LOUDNESS, true))
    /** Quiet songs turned up, loud ones down. On unless turned off. */
    val sameLoudness: StateFlow<Boolean> = _sameLoudness
    private val _skipSilence = MutableStateFlow(prefs.getBoolean(SKIP_SILENCE, false))
    /** Silence inside songs is jumped over. */
    val skipSilence: StateFlow<Boolean> = _skipSilence
    fun setSkipSilence(on: Boolean) { _skipSilence.value = on; prefs.edit().putBoolean(SKIP_SILENCE, on).apply() }

    private val _smartTrim = MutableStateFlow(prefs.getBoolean(SMART_TRIM, true))
    /** Talking at the start and end of music videos is skipped, from SponsorBlock's shared list. On unless turned off. */
    val smartTrim: StateFlow<Boolean> = _smartTrim
    fun setSmartTrim(on: Boolean) { _smartTrim.value = on; prefs.edit().putBoolean(SMART_TRIM, on).apply() }

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
    // until something is set on the ten bands, what the phone's own equalizer left behind counts, converted
    private val eqSavedOld = !prefs.contains(EQ_BANDS_KEY)
    private val _eqPreset = MutableStateFlow(prefs.getString(EQ_PRESET, null).takeIf { name -> !eqSavedOld || EQ_PRESETS.any { it.first == name } })
    /** Name of the ready-made sound in use, or null for the user's own mix. */
    val eqPreset: StateFlow<String?> = _eqPreset
    private val _eqLevels = MutableStateFlow(
        if (eqSavedOld) eqFromOldBands(eqNumbers(EQ_LEVELS))
        else eqNumbers(EQ_BANDS_KEY).takeIf { it.size == EQ_BANDS } ?: List(EQ_BANDS) { 0 },
    )
    /** Level of each of the [EQ_BANDS] bands in millibels (100 = 1 dB), low to high. */
    val eqLevels: StateFlow<List<Int>> = _eqLevels
    fun setEqOn(on: Boolean) { _eqOn.value = on; prefs.edit().putBoolean(EQ_ON, on).apply() }
    /**
     * The one way to change the sound's shape: a name to show (null for the user's own mix) and all [EQ_BANDS] levels.
     * A finger still dragging passes [save] false, and [saveEq] once it lifts.
     */
    fun setEq(preset: String?, levels: List<Int>, save: Boolean = true) {
        require(levels.size == EQ_BANDS) { "the equalizer has $EQ_BANDS bands, got ${levels.size}" }
        _eqPreset.value = preset; _eqLevels.value = levels
        if (save) saveEq()
    }
    fun saveEq() {
        prefs.edit().putString(EQ_PRESET, _eqPreset.value).putString(EQ_BANDS_KEY, _eqLevels.value.joinToString(",")).apply()
    }
    private val _eqBefore = MutableStateFlow(prefs.contains(EQ_BEFORE))
    /** Whether a setting is kept to [restoreEqBefore] to: the user's own, set aside when a headphone correction replaced it. */
    val eqBeforeSaved: StateFlow<Boolean> = _eqBefore
    /** Keeps the name, the levels and whether the equalizer is on. */
    fun saveEqBefore() {
        prefs.edit().putString(EQ_BEFORE, "${_eqPreset.value.orEmpty()}\n${_eqLevels.value.joinToString(",")}\n${_eqOn.value}").apply()
        _eqBefore.value = true
    }
    /** Puts back what [saveEqBefore] kept, or a flat curve (on or off as it is) when nothing is kept. */
    fun restoreEqBefore() {
        val kept = prefs.getString(EQ_BEFORE, null)?.split('\n')?.takeIf { it.size == 3 }
            ?.let { (n, l, on) -> Triple(n.ifEmpty { null }, l.split(',').mapNotNull { it.toIntOrNull() }, on.toBooleanStrictOrNull()) }
            ?.takeIf { it.second.size == EQ_BANDS && it.third != null }
        if (kept == null) setEq("Flat", List(EQ_BANDS) { 0 }) else { setEq(kept.first, kept.second); setEqOn(kept.third == true) }
        prefs.edit().remove(EQ_BEFORE).apply()
        _eqBefore.value = false
    }
    /**
     * A headphone correction found while the equalizer looked like [seenPreset], [seenLevels] and [seenOn]. If anything
     * changed meanwhile (a preset, Reset, a drag, Back to my curve, the switch) the result is stale and nothing is applied.
     * The first correction keeps the user's own setting, so [restoreEqBefore] can bring it back after several tries.
     */
    fun applyEqCorrection(name: String, levels: List<Int>, seenPreset: String?, seenLevels: List<Int>, seenOn: Boolean): Boolean {
        if (_eqPreset.value != seenPreset || _eqLevels.value != seenLevels || _eqOn.value != seenOn) return false
        if (seenPreset?.startsWith(AUTOEQ_PREFIX) != true) saveEqBefore()
        setEq(name, levels)
        setEqOn(true)
        return true
    }
    private fun eqNumbers(key: String) = prefs.getString(key, "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }

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
    fun setKeepScreenOn(on: Boolean) { _keepScreenOn.value = on; prefs.edit().putBoolean(KEEP_SCREEN_ON, on).apply() }
    fun setTheme(mode: ThemeMode) { _theme.value = mode; prefs.edit().putString(THEME, mode.name).apply() }

    private val _firstLaunchSeen = MutableStateFlow(prefs.getBoolean(FIRST_LAUNCH_SEEN, false))
    /** The first-launch screens were finished, skipped, or never needed (the app was updated, not installed). */
    val firstLaunchSeen: StateFlow<Boolean> = _firstLaunchSeen
    fun markFirstLaunchSeen() { _firstLaunchSeen.value = true; prefs.edit().putBoolean(FIRST_LAUNCH_SEEN, true).apply() }

    private val _artists = MutableStateFlow(readArtists())
    /** Artists picked for Home, in the order picked. */
    val artists: StateFlow<List<PickedArtist>> = _artists
    fun setArtists(list: List<PickedArtist>) {
        _artists.value = list
        prefs.edit().putString(ARTISTS, list.joinToString("\n") { listOf(it.name, it.url, it.thumbnailUrl.orEmpty()).joinToString("\t") }).apply()
    }
    private fun readArtists() = prefs.getString(ARTISTS, "").orEmpty().lines().mapNotNull { line ->
        line.split('\t').takeIf { it.size == 3 && it[0].isNotBlank() }?.let { PickedArtist(it[0], it[1], it[2].ifEmpty { null }) }
    }

    private companion object {
        const val FIRST_LAUNCH_SEEN = "first_launch_seen"
        const val ARTISTS = "picked_artists"
        const val DATA_SAVER = "data_saver"
        const val SAVE_LOW = "save_low"
        const val KEEP_SCREEN_ON = "keep_screen_on"
        const val THEME = "theme"
        const val SAME_LOUDNESS = "same_loudness"
        const val SKIP_SILENCE = "skip_silence"
        const val SMART_TRIM = "smart_trim"
        const val SPEED = "speed"
        const val PITCH = "pitch"
        const val FADE = "fade_seconds"
        const val EQ_ON = "eq_on"
        const val EQ_PRESET = "eq_preset"
        /** The bars of the phone's own equalizer, read only to carry them over. */
        const val EQ_LEVELS = "eq_levels"
        const val EQ_BANDS_KEY = "eq_bands"
        const val EQ_BEFORE = "eq_before"
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
