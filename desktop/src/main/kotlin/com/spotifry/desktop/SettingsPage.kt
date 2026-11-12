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
fun SettingsPage(services: Services, flow: BackupFlow, onEqualizer: () -> Unit = {}, modifier: Modifier = Modifier) {
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
        item { SoundGroup(settings, onEqualizer) }
        item {
            Group("Look") {
                Row("Name", "Shown in the greeting", onClick = { editName = true }) { Value(name.ifEmpty { "Not set" }) }
                HomePictureRow(services)
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
        item { MusicGroup(services) }
        item {
            Group("Storage") {
                Row("Save quality", "Songs you save to this computer") { Segments(listOf("High", "Low"), if (saveLow) 1 else 0) { settings.setSaveLow(it == 1) } }
                Row("Saved songs", "$savedCount ${if (savedCount == 1) "song" else "songs"} · ${lib.saveFolder.path}")
                services.replay?.let { ReplayRows(settings, it) }
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
                UpdateRow(services)
                Row("Music from YouTube", "Through NewPipe Extractor $NEWPIPE_VERSION.")
                // the photos' licences ask for the photographer and the licence to be named
                Row("Home pictures", "Photos from Wikimedia Commons: morning by Jakub Hałun (CC BY-SA 4.0), afternoon by PattayaPatrol (CC BY-SA 4.0), evening by Martin St-Amant (CC BY-SA 3.0), night by Vincent Lock (CC BY 2.0).")
            }
        }
        item { Spacer(Modifier.height(24.dp + LocalBottomInset.current)) }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = 26.dp)) {
        Kicker(title, Modifier.padding(start = 4.dp, bottom = 10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.ink.copy(alpha = 0.05f)).border(1.dp, Palette.ink.copy(alpha = 0.1f), RoundedCornerShape(14.dp)), content = content)
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
    androidx.compose.foundation.layout.Row(Modifier.clip(RoundedCornerShape(18.dp)).background(Palette.ink.copy(alpha = 0.08f)).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (i == selected) Palette.accent.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent).clickable { onSelect(i) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, fontSize = 13.sp, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.SemiBold, color = if (i == selected) Palette.accent else Palette.ink2) }
        }
    }
}

/** A folder as ~/Music, or ~/…/music when the whole path is too long to read at a glance. */
internal fun shortPath(dir: java.io.File): String {
    val p = dir.path.replaceFirst(System.getProperty("user.home"), "~")
    if (p.length <= 32) return p
    val first = p.substringBefore('/', "").ifEmpty { "/" + p.removePrefix("/").substringBefore('/') }
    return "$first/…/${dir.name}"
}

/** Home's banner: the picture for the time of day, or one you pick. */
@Composable
private fun HomePictureRow(services: Services) {
    val lib = services.library
    val own by lib.homePicture.collectAsState()
    val scope = rememberCoroutineScope()
    Row("Home picture", own?.let { java.io.File(it).name } ?: "Changes with the time of day", onClick = {
        scope.launch { FilePicker.picture("Pick a picture for Home")?.let { lib.setHomePicture(it.path) } }
    }) {
        if (own != null) Text("Use time of day", color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { lib.setHomePicture(null) }.padding(horizontal = 6.dp, vertical = 4.dp))
        else Value("Choose")
    }
}

@Composable
private fun MusicGroup(services: Services) {
    val folder = services.library.musicFolder
    val dir by folder.folder.collectAsState()
    val songs by folder.songs.collectAsState()
    val reading by folder.reading.collectAsState()
    val hide by services.library.settings.hideNonMusic.collectAsState()
    val scope = rememberCoroutineScope()
    Group("Music on this computer") {
        Row("Music folder", if (reading) "Looking for songs…" else "${songs.size} ${if (songs.size == 1) "song" else "songs"}, shown under Saved, On this computer", onClick = {
            scope.launch { FilePicker.folder("Pick your music folder", dir)?.let { folder.choose(it); folder.refresh() } }
        }) {
            // a long path keeps its start and its last folder, so the words on the left keep their room
            Text(shortPath(dir), color = Palette.ink2, fontSize = 14.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp))
        }
        Toggle("Hide non-music audio", "Clips under 30 seconds and recordings.", hide, services.library.settings::setHideNonMusic)
    }
}

/** The fade lengths offered for Crossfade, in seconds; the same as the phone's. */
private val FADES = listOf(0, 2, 5, 8, 12)

@Composable
private fun SoundGroup(settings: com.spotifry.music.data.Settings, onEqualizer: () -> Unit) {
    val monochrome by settings.monochrome.collectAsState()
    val jioSaavn by settings.jioSaavn.collectAsState()
    val sameLoudness by settings.sameLoudness.collectAsState()
    val skipSilence by settings.skipSilence.collectAsState()
    val smartTrim by settings.smartTrim.collectAsState()
    val fade by settings.fadeSeconds.collectAsState()
    val eqOn by settings.eqOn.collectAsState()
    val eqPreset by settings.eqPreset.collectAsState()
    Group("Sound") {
        Row("Equalizer", if (eqOn) "On · ${eqPreset?.removePrefix(com.spotifry.music.player.AUTOEQ_PREFIX) ?: "Custom"}" else "Off", onClick = onEqualizer) { Value("Open") }
        Toggle("Lossless (Monochrome)", "FLAC, 5–10× the data. Unofficial, can stop working any day.", monochrome, settings::setMonochrome)
        Toggle("JioSaavn 320 kbps", "Unofficial, may not work outside India.", jioSaavn, settings::setJioSaavn)
        Toggle("Same loudness for every song", "Quiet songs up, loud ones down.", sameLoudness, settings::setSameLoudness)
        Toggle("Skip quiet parts", "Jumps over long silences.", skipSilence, settings::setSkipSilence)
        Toggle("Smart trim", "Skips talking at the start and end of music videos.", smartTrim, settings::setSmartTrim)
        Row("Crossfade", "The end of a song fades out and the next fades in.") {
            Segments(FADES.map { if (it == 0) "Off" else "$it s" }, FADES.indexOf(fade).coerceAtLeast(0)) { settings.setFadeSeconds(FADES[it]) }
        }
    }
}

/** A row with a switch; a click anywhere on it flips it. */
@Composable
private fun Toggle(title: String, subtitle: String, on: Boolean, set: (Boolean) -> Unit) = Row(title, subtitle, onClick = { set(!on) }) { OnOffSwitch(on) }

/** A switch that only shows; the row or box around it takes the click. */
@Composable
fun OnOffSwitch(on: Boolean) {
    androidx.compose.material3.Switch(
        on, null,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = Palette.bg, checkedTrackColor = Palette.accent,
            uncheckedThumbColor = Palette.ink2, uncheckedTrackColor = Palette.ink.copy(alpha = 0.08f), uncheckedBorderColor = Palette.ink.copy(alpha = 0.25f),
        ),
    )
}

/** The phone's choices: 512 MB to 5 GB, or no limit (0). */
private val CACHE_MB = listOf(512, 1024, 2048, 5120, 0)
private fun cacheLabel(mb: Int) = when { mb == 0 -> "∞"; mb >= 1024 -> "${mb / 1024} GB"; else -> "$mb MB" }

@Composable
private fun ReplayRows(settings: com.spotifry.music.data.Settings, replay: ReplayStore) {
    val cacheMb by settings.cacheMb.collectAsState()
    var cleared by remember { mutableStateOf(0) }
    val used by produceState(-1L, cleared) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { replay.usedBytes() } }
    Row("Replay storage", "Most space for played songs. ∞ keeps them all.") {
        Segments(CACHE_MB.map(::cacheLabel), CACHE_MB.indexOf(cacheMb).coerceAtLeast(0)) { settings.setCacheMb(CACHE_MB[it]) }
    }
    Row(
        "Clear played songs kept for replay",
        if (used < 0) "Counting…" else "${sizeText(used)} of ${if (cacheMb == 0) "no limit" else cacheLabel(cacheMb)}",
        onClick = { replay.clear(); cleared++ },
    )
}

private fun sizeText(bytes: Long) = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${bytes shr 20} MB"
    else -> "${bytes shr 10} KB"
}

/** The version, when GitHub was last asked, and Check now; Update opens the download page when a newer one is out. */
@Composable
private fun UpdateRow(services: Services) {
    val updates = services.library.updates
    val available by updates.available.collectAsState()
    val checking by updates.checking.collectAsState()
    val checked by updates.lastChecked.collectAsState()
    val when_ = if (checked == 0L) "Not checked yet" else "Checked " + java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm").format(java.time.Instant.ofEpochMilli(checked).atZone(java.time.ZoneId.systemDefault()))
    val next = available
    Row("Spotifry", "Version $desktopVersion · " + when { checking -> "Checking…"; next != null -> "${next.version} is out"; else -> when_ }) {
        if (next != null) Text("Update", color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { updates.open(next) }.padding(horizontal = 6.dp, vertical = 4.dp))
        else if (!checking) Text("Check now", color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { updates.check() }.padding(horizontal = 6.dp, vertical = 4.dp))
    }
}
