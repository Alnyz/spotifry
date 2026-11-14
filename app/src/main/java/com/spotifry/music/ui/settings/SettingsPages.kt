package com.spotifry.music.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.BuildConfig
import com.spotifry.music.data.Lyrics
import com.spotifry.music.data.ThemeMode
import com.spotifry.music.data.UpdateState
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.ScreenHeader
import com.spotifry.music.ui.theme.AccentChoice

/** The frame every settings sub-page shares: a header with Back, then the cards. */
@Composable
private fun Page(title: String, onBack: () -> Unit, bottomInset: Dp, content: @Composable () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 24.dp)) {
        item { ScreenHeader(title, onBack = onBack) }
        item { content() }
    }
}

@Composable
private fun Chevron() = Icon(Icons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SwitchItem(title: String, subtitle: String, on: Boolean, set: (Boolean) -> Unit) =
    Item(title, subtitle, onClick = { set(!on) }, role = Role.Switch) { Switch(on, null, colors = switchColors()) }

@Composable
fun SoundSettings(vm: SettingsViewModel, onBack: () -> Unit, bottomInset: Dp, onOpenEqualizer: () -> Unit) {
    val s = vm.settings
    val dataSaver by s.dataSaver.collectAsStateWithLifecycle()
    val saveLow by s.saveLow.collectAsStateWithLifecycle()
    val sameLoudness by s.sameLoudness.collectAsStateWithLifecycle()
    val skipSilence by s.skipSilence.collectAsStateWithLifecycle()
    val smartTrim by s.smartTrim.collectAsStateWithLifecycle()
    val jioSaavn by s.jioSaavn.collectAsStateWithLifecycle()
    val monochrome by s.monochrome.collectAsStateWithLifecycle()
    val fade by s.fadeSeconds.collectAsStateWithLifecycle()
    val transition by s.transition.collectAsStateWithLifecycle()
    val mono by s.mono.collectAsStateWithLifecycle()
    val spatialOn by s.spatialOn.collectAsStateWithLifecycle()
    val eqOn by s.eqOn.collectAsStateWithLifecycle()
    val eqPreset by s.eqPreset.collectAsStateWithLifecycle()
    Page("Sound & playback", onBack, bottomInset) {
        Group("Quality") {
            SwitchItem("Data saver", "Lower quality on mobile data. Wi-Fi stays high.", dataSaver, s::setDataSaver)
            SwitchItem("Lossless (Monochrome)", "FLAC, 5–10× the data. Unofficial, can stop working any day.", monochrome, vm::setMonochrome)
            SwitchItem("JioSaavn 320 kbps", "Unofficial, may not work outside India.", jioSaavn, vm::setJioSaavn)
            Item("Save quality", "Songs you save to the phone", below = true) {
                Segments(listOf("High", "Low"), if (saveLow) 1 else 0, Modifier.fillMaxWidth(), fill = true) { s.setSaveLow(it == 1) }
            }
        }
        Group("Volume") {
            SwitchItem("Same loudness for every song", "Quiet songs up, loud ones down.", sameLoudness, s::setSameLoudness)
            SwitchItem("Skip silence", "Jumps over long silences.", skipSilence, s::setSkipSilence)
            if (skipSilence) SilenceControls(s)
            SwitchItem("Smart trim", "Skips talking at the start and end of music videos.", smartTrim, s::setSmartTrim)
        }
        Group("Between songs") {
            Item("Crossfade", "Songs overlap, on the beat when they can.", below = true) {
                Segments(FADES.map { if (it == 0) "Off" else "$it s" }, FADES.indexOf(fade).coerceAtLeast(0), Modifier.fillMaxWidth(), fill = true) { s.setFadeSeconds(FADES[it]) }
            }
            if (fade > 0) Item("Transition", "How one song runs into the next. Auto picks for each pair.", below = true) {
                Segments(com.spotifry.music.player.TransitionStyle.entries.map { it.label.substringBefore(' ') }, transition, Modifier.fillMaxWidth(), fill = true) { s.setTransition(it) }
            }
        }
        Group("Headphones") {
            SwitchItem("Mono audio", "Left and right mixed into one. For one earbud or hearing in one ear.", mono, s::setMono)
            SwitchItem("Spatial audio", "For headphones: a little of each side reaches the other ear, like speakers.", spatialOn, s::setSpatialOn)
            if (spatialOn) SpatialControls(s)
            Item("Equalizer", onClick = onOpenEqualizer) {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    Text(if (!eqOn) "Off" else eqPreset ?: "Custom", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Chevron()
                }
            }
        }
    }
}

@Composable
fun LookSettings(vm: SettingsViewModel, onBack: () -> Unit, bottomInset: Dp) {
    val s = vm.settings
    val theme by s.theme.collectAsStateWithLifecycle()
    val pureBlack by s.pureBlack.collectAsStateWithLifecycle()
    val accent by s.accent.collectAsStateWithLifecycle()
    val accentColor by s.accentColor.collectAsStateWithLifecycle()
    val userName by s.name.collectAsStateWithLifecycle()
    var pickAccent by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf<String?>(null) }
    Page("Look", onBack, bottomInset) {
        Group("") { Item("Name", userName.ifEmpty { "Not set" }, onClick = { editName = userName }) { Chevron() } }
        Group("Colours") {
            Item("Theme", below = true) {
                Segments(listOf("Phone", "Light", "Dark"), theme.ordinal, Modifier.fillMaxWidth(), fill = true) { s.setTheme(ThemeMode.entries[it]) }
            }
            SwitchItem("Pure black", "Black instead of dark grey. Saves battery on OLED.", pureBlack, s::setPureBlack)
            Item("Accent colour", AccentChoice.of(accent).label, onClick = { pickAccent = true }) {
                androidx.compose.foundation.layout.Box(
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).padding(3.dp).size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
    if (pickAccent) AccentPicker(accent, accentColor, { c, color -> s.setAccent(c, color); pickAccent = false }, { pickAccent = false })
    editName?.let { text ->
        AlertDialog(
            onDismissRequest = { editName = null },
            title = { Text("Your name") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    text, { editName = com.spotifry.music.data.cutName(it.replace('\n', ' ')) }, singleLine = true,
                    placeholder = { Text("Name or nickname") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words, autoCorrectEnabled = false),
                )
            },
            confirmButton = { TextButton({ s.setName(text); editName = null }) { Text("Save") } },
            dismissButton = { TextButton({ editName = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun PhoneSettings(vm: SettingsViewModel, onBack: () -> Unit, bottomInset: Dp) {
    val hideNonMusic by vm.settings.hideNonMusic.collectAsStateWithLifecycle()
    Page("Music on your phone", onBack, bottomInset) {
        Group("") { SwitchItem("Hide non-music audio", "Clips under 30 seconds, voice notes, recordings, system sounds.", hideNonMusic, vm.settings::setHideNonMusic) }
    }
}

@Composable
fun LyricsSettings(vm: SettingsViewModel, onBack: () -> Unit, bottomInset: Dp) {
    val lyricsOff by vm.settings.lyricsOff.collectAsStateWithLifecycle()
    Page("Lyrics", onBack, bottomInset) {
        Group("Sources") {
            Lyrics.SOURCES.forEach { name ->
                val on = name !in lyricsOff
                SwitchItem(
                    name, when (name) {
                        Lyrics.BETTER_LYRICS -> "Word by word."
                        Lyrics.LRC_RED -> "Word by word."
                        Lyrics.PORTATO -> "Word by word, a big catalogue."
                        Lyrics.UNISON -> "Written by listeners."
                        else -> "Line by line, the last choice."
                    }, on,
                ) { vm.settings.setLyricsSource(name, it) }
            }
        }
    }
}

@Composable
fun HistorySettings(vm: SettingsViewModel, onBack: () -> Unit, bottomInset: Dp) {
    val searches by vm.recentSearches.list.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    Page("History", onBack, bottomInset) {
        Group("") {
            Item("Clear recent searches", if (searches.isEmpty()) "Nothing to clear" else "${searches.size} saved", onClick = vm::clearSearches)
            Item("Clear listening history", "Empties Recently played, Most played and Taste", danger = true, onClick = { confirm = true })
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Clear listening history?") },
        text = { Text("Recently played, Most played and Taste start empty. Saved songs and playlists stay.") },
        confirmButton = { TextButton({ vm.clearHistory(); confirm = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Clear") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Keep it") } },
    )
}

@Composable
fun AboutSettings(updates: com.spotifry.music.data.Updates, onBack: () -> Unit, bottomInset: Dp) {
    val update by updates.state.collectAsStateWithLifecycle()
    val checking by updates.checking.collectAsStateWithLifecycle()
    val checked by updates.lastChecked.collectAsStateWithLifecycle()
    val newer = when (val u = update) {
        is UpdateState.Available -> u.release; is UpdateState.Failed -> u.release; is UpdateState.Ready -> u.release
        is UpdateState.Downloading -> u.release; UpdateState.None -> null
    }
    Page("About", onBack, bottomInset) {
        Group("") {
            Item(
                "Spotifry",
                "Version ${BuildConfig.VERSION_NAME}" + when (update) {
                    is UpdateState.Downloading -> " · downloading ${newer!!.version}…"
                    UpdateState.None -> ""
                    else -> " · ${newer!!.version} is out"
                },
                onClick = if (newer != null && update !is UpdateState.Downloading) updates::update else null,
            ) { if (newer != null && update !is UpdateState.Downloading) Text("Update", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
            Item(
                "Check for updates",
                when {
                    checking -> "Checking…"
                    checked == 0L -> "Not checked yet"
                    update == UpdateState.None -> "You have the newest version · checked ${checkedText(checked)}"
                    else -> "Last checked ${checkedText(checked)}"
                },
                onClick = updates::check,
            )
            Item("Music from YouTube", "Through NewPipe Extractor ${BuildConfig.NEWPIPE_VERSION}.")
            Item("Lyrics", "Each source gets the song's title, artist and length.")
        }
    }
}

@Composable
fun ChangeLogSettings(updates: com.spotifry.music.data.Updates, onBack: () -> Unit, bottomInset: Dp) {
    val log by updates.log.collectAsStateWithLifecycle()
    val failed by updates.logFailed.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { updates.loadLog() }
    val scheme = MaterialTheme.colorScheme
    Page("Change log", onBack, bottomInset) {
        if (failed) Text(
            if (log.isEmpty()) "Couldn't load the change log. Try again when you're online." else "Couldn't load, showing the last list.",
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        ) else if (log.isEmpty()) Text("Loading…", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        log.forEach { e ->
            val mine = e.version == BuildConfig.VERSION_NAME
            Group("") {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        Text("Version ${e.version}", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                        if (mine) Text("You have this", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                    }
                    logDate(e.date)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                    val notes = com.spotifry.music.data.plainNotes(e.notes)
                    if (notes.isNotEmpty()) Text(notes, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                }
            }
        }
    }
}

/** "14 Nov 2026" from GitHub's "2026-11-14T09:12:00Z"; null when there is no date. */
private fun logDate(iso: String): String? = runCatching {
    java.time.LocalDate.parse(iso.take(10)).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
}.getOrNull()
