package com.spotifry.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsPage(services: Services, flow: BackupFlow, modifier: Modifier = Modifier) {
    val lib = services.library
    val settings = lib.settings
    val name by settings.name.collectAsState()
    val theme by settings.theme.collectAsState()
    val accent by settings.accent.collectAsState()
    val saveLow by settings.saveLow.collectAsState()
    val savedCount by produceState(0, lib) { value = lib.db.songs().savedCount() }
    var editName by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (editName) NameDialog("Your name", "Save", name, { editName = false }) { settings.setName(it); editName = false }
    if (clearing) ConfirmDialog("Clear listening history?", "Empties Recently played, Most played and your listening. Saved songs and playlists stay.", "Clear", { clearing = false }) {
        scope.launch { lib.db.history().clear(); lib.db.plays().clear(); lib.db.listens().clear() }; clearing = false
    }

    LazyColumn(modifier.widthIn(max = 760.dp)) {
        item { PageHeader("Settings") }
        item {
            Group("Look") {
                Row("Name", "Shown in the greeting", onClick = { editName = true }) { Value(name.ifEmpty { "Not set" }) }
                Row("Theme") { Segments(listOf("System", "Light", "Dark"), when (theme) { ThemeMode.PHONE -> 0; ThemeMode.LIGHT -> 1; ThemeMode.DARK -> 2 }) { settings.setTheme(ThemeMode.entries[it]) } }
                Row("Accent colour", AccentChoice.of(accent).label) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AccentChoice.entries.forEach { c ->
                            val on = AccentChoice.of(accent) == c
                            Box(
                                Modifier.size(26.dp).clip(CircleShape).background(if (Palette.dark) c.dark else c.light)
                                    .then(if (on) Modifier.border(2.dp, Palette.ink, CircleShape) else Modifier).clickable { settings.setAccent(c.name, (if (Palette.dark) c.dark else c.light).toArgb()) },
                            )
                        }
                    }
                }
            }
        }
        item {
            Group("Saved songs") {
                Row("Save quality", "Songs you save to this computer") { Segments(listOf("High", "Low"), if (saveLow) 1 else 0) { settings.setSaveLow(it == 1) } }
                Row("Saved songs", "$savedCount ${if (savedCount == 1) "song" else "songs"} · ${lib.saveFolder.path}")
            }
        }
        item {
            Group("Backup") {
                Row("Save backup", "Playlists, history, trims and settings in one file.", onClick = flow::save) { Value("Save") }
                Row("Restore backup", "Brings a backup into this computer. A backup from the phone works too.", onClick = flow::choose) { Value("Restore") }
            }
        }
        item {
            Group("History") {
                Row("Clear listening history", "Empties Recently played, Most played and your listening", danger = true, onClick = { clearing = true })
            }
        }
        item {
            Group("About") {
                Row("Spotifry", "Version $APP_VERSION")
                Row("Music from YouTube", "Through NewPipe Extractor $NEWPIPE_VERSION.")
            }
        }
        item { Spacer(Modifier.height(24.dp + LocalBottomInset.current)) }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = 26.dp)) {
        Text(title, color = Palette.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.surface).border(1.dp, Palette.line, RoundedCornerShape(18.dp)), content = content)
    }
}

@Composable
private fun Row(title: String, subtitle: String? = null, danger: Boolean = false, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (danger) Palette.danger else Palette.ink)
            if (subtitle != null) Text(subtitle, color = Palette.ink2, fontSize = 13.sp)
        }
        trailing()
    }
}

@Composable
private fun Value(text: String) = Text(text, color = Palette.ink2, fontSize = 14.sp)

@Composable
private fun Segments(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.clip(RoundedCornerShape(18.dp)).background(Palette.high).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (i == selected) Palette.accent.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent).clickable { onSelect(i) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, fontSize = 13.sp, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.SemiBold, color = if (i == selected) Palette.accent else Palette.ink2) }
        }
    }
}
