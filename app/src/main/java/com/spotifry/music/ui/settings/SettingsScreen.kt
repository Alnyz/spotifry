package com.spotifry.music.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.BuildConfig
import com.spotifry.music.data.ThemeMode
import com.spotifry.music.ui.components.ScreenHeader
import com.spotifry.music.data.UpdateState

@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit, bottomInset: Dp, modifier: Modifier = Modifier, updates: com.spotifry.music.data.Updates? = null, onOpenEqualizer: () -> Unit = {}) {
    val dataSaver by vm.settings.dataSaver.collectAsStateWithLifecycle()
    val saveLow by vm.settings.saveLow.collectAsStateWithLifecycle()
    val theme by vm.settings.theme.collectAsStateWithLifecycle()
    val sameLoudness by vm.settings.sameLoudness.collectAsStateWithLifecycle()
    val skipSilence by vm.settings.skipSilence.collectAsStateWithLifecycle()
    val fade by vm.settings.fadeSeconds.collectAsStateWithLifecycle()
    val eqOn by vm.settings.eqOn.collectAsStateWithLifecycle()
    val eqPreset by vm.settings.eqPreset.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val searches by vm.recentSearches.list.collectAsStateWithLifecycle()
    var confirmHistory by remember { mutableStateOf(false) }
    var pickAccent by remember { mutableStateOf(false) }
    val pureBlack by vm.settings.pureBlack.collectAsStateWithLifecycle()
    val accent by vm.settings.accent.collectAsStateWithLifecycle()
    val accentColor by vm.settings.accentColor.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.count() }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 24.dp)) {
        item { ScreenHeader("Settings", onBack = onBack) }
        item {
            Group("Sound") {
                Item("Data saver", "Lower quality on mobile data, about a third of the data. Wi-Fi stays high.", onClick = { vm.settings.setDataSaver(!dataSaver) }, role = Role.Switch) {
                    Switch(dataSaver, null, colors = switchColors())
                }
                Item(
                    "Same loudness for every song", "Quiet songs are turned up and loud ones down, so you don't reach for the volume between songs.",
                    onClick = { vm.settings.setSameLoudness(!sameLoudness) }, role = Role.Switch,
                ) {
                    Switch(sameLoudness, null, colors = switchColors())
                }
                Item(
                    "Skip quiet parts", "Jumps over silence, like long quiet intros, endings and gaps between tracks on live albums.",
                    onClick = { vm.settings.setSkipSilence(!skipSilence) }, role = Role.Switch,
                ) {
                    Switch(skipSilence, null, colors = switchColors())
                }
                Item("Fade between songs", "The end of a song fades out and the next one fades in.", below = true) {
                    Segments(FADES.map { if (it == 0) "Off" else "$it s" }, FADES.indexOf(fade).coerceAtLeast(0)) { vm.settings.setFadeSeconds(FADES[it]) }
                }
                Item("Equalizer", if (!eqOn) "Off" else eqPreset ?: "Custom", onClick = onOpenEqualizer) {
                    androidx.compose.material3.Icon(com.spotifry.music.ui.Icons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Item("Save quality", "Songs you save to the phone") {
                    Segments(listOf("High", "Low"), if (saveLow) 1 else 0) { vm.settings.setSaveLow(it == 1) }
                }
            }
            Group("Look") {
                Item("Theme") {
                    Segments(listOf("Phone", "Light", "Dark"), theme.ordinal) { vm.settings.setTheme(ThemeMode.entries[it]) }
                }
                Item(
                    "Pure black", "Black instead of dark grey when the app is dark. Saves battery on phones with an OLED screen.",
                    onClick = { vm.settings.setPureBlack(!pureBlack) }, role = Role.Switch,
                ) { Switch(pureBlack, null, colors = switchColors()) }
                Item("Accent colour", com.spotifry.music.ui.theme.AccentChoice.of(accent).label, onClick = { pickAccent = true }) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).padding(3.dp).size(26.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            Group("Storage") {
                Item("Saved songs", storage?.let { "${it.savedCount} ${if (it.savedCount == 1) "song" else "songs"} · ${size(it.savedBytes)}" } ?: "Counting…")
                Item("Clear played songs kept for replay", storage?.let { "${size(it.songCacheBytes)} of 500 MB · they download again when played" } ?: "Counting…", onClick = vm::clearSongCache)
                Item("Clear covers kept for speed", storage?.let { "${size(it.coverBytes)} · they load again when shown" } ?: "Counting…", onClick = vm::clearCovers)
            }
            Group("History") {
                Item("Clear recent searches", if (searches.isEmpty()) "Nothing to clear" else "${searches.size} saved", onClick = vm::clearSearches)
                Item("Clear listening history", "Empties Recently played and Most played", danger = true, onClick = { confirmHistory = true })
            }
            Group("About") {
                if (updates != null) {
                    val update by updates.state.collectAsStateWithLifecycle()
                    val checking by updates.checking.collectAsStateWithLifecycle()
                    val checked by updates.lastChecked.collectAsStateWithLifecycle()
                    val newer = when (val u = update) {
                        is UpdateState.Available -> u.release; is UpdateState.Failed -> u.release; is UpdateState.Ready -> u.release
                        is UpdateState.Downloading -> u.release; UpdateState.None -> null
                    }
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
                } else Item("Spotifry", "Version ${BuildConfig.VERSION_NAME}")
                Item("Music from YouTube", "Through NewPipe Extractor ${BuildConfig.NEWPIPE_VERSION}. If search breaks, this needs an update.")
                Item("Lyrics from BetterLyrics and LRCLIB", "Sends the song's title, artist and length to lyrics-api.boidu.dev and lrclib.net")
            }
        }
    }
    if (pickAccent) AccentPicker(accent, accentColor, { c, color -> vm.settings.setAccent(c, color); pickAccent = false }, { pickAccent = false })
    if (confirmHistory) AlertDialog(
        onDismissRequest = { confirmHistory = false },
        title = { Text("Clear listening history?") },
        text = { Text("Recently played and Most played start empty. Saved songs and playlists stay.") },
        confirmButton = { TextButton({ vm.clearHistory(); confirmHistory = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Clear") } },
        dismissButton = { TextButton({ confirmHistory = false }) { Text("Keep it") } },
    )
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(title, Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)) {
        content()
    }
}

@Composable
private fun Item(
    title: String, subtitle: String? = null, danger: Boolean = false, onClick: (() -> Unit)? = null, role: Role? = null,
    below: Boolean = false, trailing: @Composable () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val text = @Composable { m: Modifier ->
        Column(m) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (danger) scheme.error else scheme.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
    val box = Modifier.fillMaxWidth().heightIn(min = 60.dp)
        .let { if (onClick != null) it.clickable(role = role, onClick = onClick) else it }
        .padding(horizontal = 16.dp, vertical = 12.dp)
    // a wide control (five choices) goes under the text instead of squeezing it
    if (below) Column(box, Arrangement.spacedBy(10.dp)) { text(Modifier); trailing() }
    else Row(box, Arrangement.spacedBy(14.dp), Alignment.CenterVertically) { text(Modifier.weight(1f)); trailing() }
    HorizontalDivider(color = scheme.outlineVariant)
}

/** A small row of choices, one picked. */
@Composable
internal fun Segments(options: List<String>, selected: Int, modifier: Modifier = Modifier, fill: Boolean = false, onPick: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.clip(CircleShape).background(scheme.surfaceContainerHigh).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label,
                // the picked one is filled with the accent, so it reads at a glance in light and dark
                (if (fill) Modifier.weight(1f) else Modifier).clip(CircleShape).background(if (on) scheme.primary else scheme.surfaceContainerHigh)
                    .clickable(role = Role.RadioButton, onClickLabel = label) { onPick(i) }.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge, color = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

private val FADES = listOf(0, 2, 5, 8, 12)

// off must still read as a switch on the dark card: a lighter track, an outline and a pale knob
@Composable
internal fun switchColors() = SwitchDefaults.colors(
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** "today, 09.12", "yesterday, 21.40" or "3 Nov, 09.12": the phone's own time format. */
@Composable
private fun checkedText(at: Long): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(at))
    val days = (java.time.LocalDate.now().toEpochDay() - java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay())
    return when (days) {
        0L -> "today, $time"
        1L -> "yesterday, $time"
        else -> android.text.format.DateUtils.formatDateTime(context, at, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH) + ", $time"
    }
}
