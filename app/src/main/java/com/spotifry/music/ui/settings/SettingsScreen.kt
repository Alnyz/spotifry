package com.spotifry.music.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import com.spotifry.music.Routes
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

/** The main Settings page: category rows with a live line each. Every option lives on the page behind its row. */
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit, bottomInset: Dp, onOpen: (String) -> Unit, modifier: Modifier = Modifier, updates: com.spotifry.music.data.Updates? = null) {
    val s = vm.settings
    val fade by s.fadeSeconds.collectAsStateWithLifecycle()
    val eqOn by s.eqOn.collectAsStateWithLifecycle()
    val eqPreset by s.eqPreset.collectAsStateWithLifecycle()
    val monochrome by s.monochrome.collectAsStateWithLifecycle()
    val theme by s.theme.collectAsStateWithLifecycle()
    val accent by s.accent.collectAsStateWithLifecycle()
    val hideNonMusic by s.hideNonMusic.collectAsStateWithLifecycle()
    val lyricsOff by s.lyricsOff.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.count() }

    val sound = listOfNotNull(
        if (fade > 0) "Crossfade $fade s" else null,
        if (eqOn) "Equalizer: ${eqPreset ?: "Custom"}" else null,
    ).joinToString(", ").ifEmpty { if (monochrome) "Lossless on" else "Default" }
    val sources = com.spotifry.music.data.Lyrics.SOURCES
    val checked = updates?.lastChecked?.collectAsStateWithLifecycle()?.value ?: 0L

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 24.dp)) {
        item { ScreenHeader("Settings", onBack = onBack) }
        if (updates != null) item { UpdateBanner(updates) }
        item {
            Group("") {
                NavRow(com.spotifry.music.ui.Icons.Mix, "Sound & playback", sound) { onOpen(Routes.SETTINGS_SOUND) }
                NavRow(com.spotifry.music.ui.Icons.Palette, "Look", "${when (theme) { ThemeMode.PHONE -> "Phone theme"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" }}, ${com.spotifry.music.ui.theme.AccentChoice.of(accent).label} accent") { onOpen(Routes.SETTINGS_LOOK) }
                NavRow(com.spotifry.music.ui.Icons.Phone, "Music on your phone", if (hideNonMusic) "Hiding clips and voice notes" else "Showing everything") { onOpen(Routes.SETTINGS_PHONE) }
                NavRow(com.spotifry.music.ui.Icons.Lyrics, "Lyrics", "${sources.count { it !in lyricsOff }} of ${sources.size} sources on") { onOpen(Routes.SETTINGS_LYRICS) }
            }
            Group("") {
                NavRow(com.spotifry.music.ui.Icons.Storage, "Storage & backup", storage?.let { "${Formatter.formatShortFileSize(context, it.savedBytes + it.songCacheBytes + it.coverBytes + it.dbBytes)} used by Spotifry" } ?: "Counting…") { onOpen(Routes.STORAGE) }
                NavRow(com.spotifry.music.ui.Icons.History, "History", "Searches and listening history") { onOpen(Routes.SETTINGS_HISTORY) }
            }
            Group("") {
                NavRow(com.spotifry.music.ui.Icons.Info, "About", "Version ${BuildConfig.VERSION_NAME}" + if (checked > 0) " · checked ${checkedText(checked)}" else "") { onOpen(Routes.SETTINGS_ABOUT) }
            }
        }
    }
}

/** Shown only when a newer version is out (or being fetched). */
@Composable
private fun UpdateBanner(updates: com.spotifry.music.data.Updates) {
    val update by updates.state.collectAsStateWithLifecycle()
    val newer = when (val u = update) {
        is UpdateState.Available -> u.release; is UpdateState.Failed -> u.release; is UpdateState.Ready -> u.release
        is UpdateState.Downloading -> u.release; UpdateState.None -> null
    } ?: return
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(scheme.primary.copy(alpha = .12f))
            .border(1.dp, scheme.primary.copy(alpha = .4f), RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Version ${newer.version} is out", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
            Text(if (update is UpdateState.Downloading) "Downloading…" else "You have ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        if (update !is UpdateState.Downloading) androidx.compose.material3.Button(updates::update, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary)) { Text("Update") }
    }
}

/** A category: icon, title, a line on what is set now, and a chevron. */
@Composable
internal fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, summary: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerHigh), Alignment.Center) {
            androidx.compose.material3.Icon(icon, null, Modifier.size(22.dp), tint = scheme.onSurface)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, maxLines = 1)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        androidx.compose.material3.Icon(com.spotifry.music.ui.Icons.Chevron, null, tint = scheme.onSurfaceVariant)
    }
    HorizontalDivider(color = scheme.outlineVariant)
}

@Composable
internal fun Group(title: String, content: @Composable () -> Unit) {
    if (title.isNotEmpty()) Text(title, Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 12.dp))
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)) {
        content()
    }
}

@Composable
internal fun Item(
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
                    .clickable(role = Role.RadioButton, onClickLabel = label) { onPick(i) }.padding(horizontal = if (fill) 8.dp else 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge, color = if (on) scheme.onPrimary else scheme.onSurfaceVariant, maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

internal fun cacheLabel(mb: Int) = when { mb == 0 -> "∞"; mb >= 1024 -> "${mb / 1024} GB"; else -> "$mb MB" }
internal val CACHE_MB = listOf(512, 1024, 2048, 5120, 0) // 0 = no limit
internal val FADES = listOf(0, 2, 5, 8, 12)

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
internal fun checkedText(at: Long): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(at))
    val days = (java.time.LocalDate.now().toEpochDay() - java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay())
    return when (days) {
        0L -> "today, $time"
        1L -> "yesterday, $time"
        else -> android.text.format.DateUtils.formatDateTime(context, at, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH) + ", $time"
    }
}
