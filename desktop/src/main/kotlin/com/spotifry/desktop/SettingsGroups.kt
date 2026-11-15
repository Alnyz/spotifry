package com.spotifry.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.Settings
import com.spotifry.music.data.ThemeMode
import com.spotifry.music.player.SilenceRules
import com.spotifry.music.player.SpatialSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
private fun Section(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = 22.dp)) {
        if (title != null) Kicker(title, Modifier.padding(start = 4.dp, bottom = 10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.surface).border(1.dp, Palette.line, RoundedCornerShape(14.dp)), content = content)
    }
}

/** One row of a card: the words on the left, the control on the right. A row with [onClick] is one big button; [indent] marks the options of the row above. */
@Composable
private fun Item(title: String, subtitle: String? = null, danger: Boolean = false, indent: Boolean = false, onClick: (() -> Unit)? = null, below: (@Composable () -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val src = remember { MutableInteractionSource() }
    val line = Palette.line
    Column(
        Modifier.fillMaxWidth().drawBehind { drawLine(line, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f) }
            .keyRing(src, RoundedCornerShape(0.dp))
            .then(if (onClick != null) Modifier.clickable(src, null, onClick = onClick) else Modifier)
            .padding(start = if (indent) 40.dp else 18.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 38.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (danger) Palette.danger else Palette.ink)
                if (subtitle != null) Text(subtitle, color = Palette.ink2, fontSize = 13.sp)
            }
            trailing()
        }
        below?.invoke()
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, on: Boolean, set: (Boolean) -> Unit) = Item(title, subtitle, onClick = { set(!on) }) { OnOffSwitch(on) }

@Composable
private fun Value(text: String) = Text(text, color = Palette.ink2, fontSize = 14.sp)

@Composable
private fun Action(text: String, danger: Boolean = false) = Text(text, color = if (danger) Palette.danger else Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Segments(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(18.dp)).background(Palette.ink.copy(alpha = 0.08f)).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (i == selected) Palette.accent.copy(alpha = 0.2f) else Color.Transparent).clickable { onSelect(i) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, fontSize = 13.sp, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.SemiBold, color = if (i == selected) Palette.accent else Palette.ink2, maxLines = 1) }
        }
    }
}

/** A sliding row: the value shows beside the slider; [onDone] runs when the slider is let go. In a narrow pane the slider drops under the words. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Slide(title: String, subtitle: String?, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: () -> Unit) {
    val colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent, inactiveTrackColor = Palette.ink.copy(alpha = 0.14f))
    @Composable fun control(modifier: Modifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Slider(
            v, onChange, Modifier.weight(1f), valueRange = range, onValueChangeFinished = onDone, colors = colors,
            thumb = { Box(Modifier.size(18.dp).clip(CircleShape).background(Palette.accent)) },
            track = { SliderDefaults.Track(it, Modifier.height(4.dp), colors = colors, drawStopIndicator = null, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 0.dp) },
        )
        Text(value, Modifier.width(72.dp), textAlign = TextAlign.End, fontSize = 14.sp, color = Palette.ink)
    }
    BoxWithConstraints {
        if (maxWidth >= 600.dp) Item(title, subtitle, indent = true) { control(Modifier.width(300.dp)) }
        else Item(title, subtitle, indent = true, below = { control(Modifier.fillMaxWidth()) })
    }
}

/** A folder as ~/Music, or ~/…/music when the whole path is too long to read at a glance. */
internal fun shortPath(dir: java.io.File): String {
    val p = dir.path.replaceFirst(System.getProperty("user.home"), "~")
    if (p.length <= 32) return p
    val first = p.substringBefore('/', "").ifEmpty { "/" + p.removePrefix("/").substringBefore('/') }
    return "$first/…/${dir.name}"
}

/** The fade lengths offered for Crossfade, in seconds; the same as the phone's. */
private val FADES = listOf(0, 2, 5, 8, 12)

/** In the order of the phone's TransitionStyle, which is how [Settings.transition] counts. */
private val TRANSITIONS = listOf("Silk blend", "Bass swap", "Filter sweep", "Auto")

@Composable
internal fun SoundGroup(services: Services, onEqualizer: () -> Unit) {
    val lib = services.library
    val s = lib.settings
    val saveLow by s.saveLow.collectAsState()
    val monochrome by s.monochrome.collectAsState()
    val jioSaavn by s.jioSaavn.collectAsState()
    val sameLoudness by s.sameLoudness.collectAsState()
    val skipSilence by s.skipSilence.collectAsState()
    val smartTrim by s.smartTrim.collectAsState()
    val fade by s.fadeSeconds.collectAsState()
    val transition by s.transition.collectAsState()
    val mono by s.mono.collectAsState()
    val spatialOn by s.spatialOn.collectAsState()
    val eqOn by s.eqOn.collectAsState()
    val eqPreset by s.eqPreset.collectAsState()
    val keep by lib.keepInTray.collectAsState()
    Section("Quality") {
        Item("Save quality", "Songs you save to this computer") { Segments(listOf("High", "Low"), if (saveLow) 1 else 0) { s.setSaveLow(it == 1) } }
        Toggle("Lossless (Monochrome)", "FLAC, 5–10× the data. Unofficial, can stop working any day.", monochrome, s::setMonochrome)
        Toggle("JioSaavn 320 kbps", "Unofficial, may not work outside India.", jioSaavn, s::setJioSaavn)
    }
    Section("Volume") {
        Toggle("Same loudness for every song", "Quiet songs up, loud ones down.", sameLoudness, s::setSameLoudness)
        Toggle("Skip silence", "Jumps over long silences.", skipSilence, s::setSkipSilence)
        if (skipSilence) SilenceRows(s)
        Toggle("Smart trim", "Skips talking at the start and end of music videos.", smartTrim, s::setSmartTrim)
    }
    Section("Between songs") {
        Item("Crossfade", "The end of a song fades out and the next fades in.") {
            Segments(FADES.map { if (it == 0) "Off" else "$it s" }, FADES.indexOf(fade).coerceAtLeast(0)) { s.setFadeSeconds(FADES[it]) }
        }
        if (fade > 0) Item("Transition style", "How one song runs into the next. Auto picks for each pair.") { Segments(TRANSITIONS, transition) { s.setTransition(it) } }
    }
    Section("Headphones") {
        Toggle("Mono audio", "Left and right mixed into one. For one earbud or hearing in one ear.", mono, s::setMono)
        Toggle("Spatial audio", "For headphones: a little of each side reaches the other ear, like speakers.", spatialOn, s::setSpatialOn)
        if (spatialOn) SpatialRows(s)
        Item("Equalizer", if (eqOn) "On · ${eqPreset?.removePrefix(com.spotifry.music.player.AUTOEQ_PREFIX) ?: "Custom"}" else "Off", onClick = onEqualizer) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { Value("Open"); Icon(Icons.Chevron, null, Modifier.size(20.dp), tint = Palette.ink2) }
        }
    }
    Section("Window") {
        Toggle("Keep playing in the tray", "Closing the window hides it and the music goes on. Use Quit below to stop. Not every desktop shows the tray icon's menu.", keep, lib::setKeepInTray)
        Item("Quit Spotifry", "Stops the music and closes the app.", danger = true, onClick = { services.quit() })
    }
}

/** A negative number with a real minus sign. */
private fun signed(n: Int) = n.toString().replace('-', '−')

@Composable
private fun SilenceRows(s: Settings) {
    val db by s.silenceDb.collectAsState()
    val cut by s.silenceCut.collectAsState()
    Slide("How quiet counts as silence", "${signed(db)} dB · ${if (db <= -60) "only true silence" else if (db >= -35) "soft singing may go too" else "real gaps"}", "${signed(db)} dB", db.toFloat(),
        SilenceRules.MIN_DB.toFloat()..SilenceRules.MAX_DB.toFloat(), { s.setSilence(it.toInt(), cut, false) }) { s.setSilence(db, cut) }
    Slide("How much of the silence is cut", "$cut% · a new value is heard from the next song", "$cut%", cut.toFloat(),
        SilenceRules.MIN_CUT_PERCENT.toFloat()..100f, { s.setSilence(db, it.toInt(), false) }) { s.setSilence(db, cut) }
    Item("Reset", "Back to ${signed(SilenceRules.DEFAULT_DB)} dB and ${SilenceRules.DEFAULT_CUT_PERCENT}%", indent = true, onClick = s::resetSilence) { Action("Reset") }
}

/** Light, Natural and Strong, then the three sliders behind them. A slider that doesn't match a preset leaves none picked. */
@Composable
private fun SpatialRows(s: Settings) {
    val t by s.spatialTune.collectAsState()
    Item("Strength", indent = true) { Segments(SpatialSetting.PRESETS.map { it.first }, SpatialSetting.PRESETS.indexOfFirst { it.second == t }) { s.setSpatialTune(SpatialSetting.PRESETS[it].second) } }
    Slide("Head shadow", "Where the far ear starts to lose treble", "${t.shadowHz} Hz", t.shadowHz.toFloat(), 300f..1500f, { s.setSpatialTune(t.copy(shadowHz = it.toInt()), false) }) { s.setSpatialTune(s.spatialTune.value) }
    Slide("Crossfeed", "How much reaches the other ear", "−${t.crossDb} dB", t.crossDb.toFloat(), 3f..12f, { s.setSpatialTune(t.copy(crossDb = it.toInt()), false) }) { s.setSpatialTune(s.spatialTune.value) }
    Slide("Amount", null, "${t.amount}%", t.amount.toFloat(), 0f..100f, { s.setSpatialTune(t.copy(amount = it.toInt()), false) }) { s.setSpatialTune(s.spatialTune.value) }
}

@Composable
internal fun LookGroup(services: Services, onEditName: () -> Unit, onPickColour: () -> Unit) {
    val lib = services.library
    val settings = lib.settings
    val name by settings.name.collectAsState()
    val theme by settings.theme.collectAsState()
    val accent by settings.accent.collectAsState()
    val own by lib.homePicture.collectAsState()
    val scope = rememberCoroutineScope()
    Section(null) {
        Item("Name", "Shown in the greeting", onClick = onEditName) { Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { Value(name.ifEmpty { "Not set" }); Action("Edit") } }
        Item("Home picture", own?.let { java.io.File(it).name } ?: "Changes with the time of day", onClick = { scope.launch { FilePicker.picture("Pick a picture for Home")?.let { lib.setHomePicture(it.path) } } }) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (own != null) Text("Use time of day", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { lib.setHomePicture(null) }.padding(horizontal = 6.dp))
                Action(if (own != null) "Change" else "Choose")
            }
        }
    }
    Section("Colours") {
        Item("Theme") { Segments(listOf("System", "Light", "Dark"), theme.ordinal) { settings.setTheme(ThemeMode.entries[it]) } }
        Item("Accent colour", AccentChoice.of(accent).label) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccentChoice.entries.forEach { c ->
                    val on = AccentChoice.of(accent) == c
                    val custom = c == AccentChoice.CUSTOM
                    val fill = if (custom && on) Palette.accent else if (Palette.dark) c.dark else c.light
                    Box(
                        Modifier.size(28.dp).clip(CircleShape)
                            // Custom is a rainbow until it is the one in use
                            .then(if (custom && !on) Modifier.background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red))) else Modifier.background(fill))
                            .then(if (on) Modifier.border(2.dp, Palette.ink, CircleShape) else Modifier)
                            .clickable { if (custom) onPickColour() else settings.setAccent(c.name, (if (Palette.dark) c.dark else c.light).toArgb()) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun MusicGroup(services: Services) {
    val folder = services.library.musicFolder
    val dir by folder.folder.collectAsState()
    val songs by folder.songs.collectAsState()
    val reading by folder.reading.collectAsState()
    val hide by services.library.settings.hideNonMusic.collectAsState()
    val scope = rememberCoroutineScope()
    Section(null) {
        Item("Music folder", if (reading) "Looking for songs…" else "${songs.size} ${if (songs.size == 1) "song" else "songs"}, shown under Saved, On this computer", onClick = {
            scope.launch { FilePicker.folder("Pick your music folder", dir)?.let { folder.choose(it); folder.refresh() } }
        }) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                // a long path keeps its start and its last folder, so the words on the left keep their room
                Text(shortPath(dir), color = Palette.ink2, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                Action("Change")
            }
        }
        Toggle("Hide non-music audio", "Clips under 30 seconds and recordings.", hide, services.library.settings::setHideNonMusic)
    }
}

/** Each lyrics source on or off; a song's title, artist and length go to every source that is on. */
@Composable
internal fun LyricsGroup(settings: Settings) {
    val off by settings.lyricsOff.collectAsState()
    val L = com.spotifry.music.data.Lyrics
    Section(null) {
        L.SOURCES.forEach { name ->
            Toggle(name, when (name) {
                L.BETTER_LYRICS, L.LRC_RED -> "Word by word."
                L.PORTATO -> "Word by word, a big catalogue."
                L.UNISON -> "Written by listeners."
                else -> "Line by line, the last choice."
            }, name !in off) { settings.setLyricsSource(name, it) }
        }
    }
    Text("Each source gets the song's title, artist and length.", color = Palette.ink2, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 12.dp))
}

/** The phone's choices: 512 MB to 5 GB, or no limit (0). */
private val CACHE_MB = listOf(512, 1024, 2048, 5120, 0)
private fun cacheLabel(mb: Int) = when { mb == 0 -> "∞"; mb >= 1024 -> "${mb / 1024} GB"; else -> "$mb MB" }

@Composable
internal fun StorageGroup(services: Services, flow: BackupFlow, use: StorageUse?, onChanged: () -> Unit) {
    val lib = services.library
    val settings = lib.settings
    val cacheMb by settings.cacheMb.collectAsState()
    val savedCount by produceState(0, lib) { value = lib.db.songs().savedCount() }
    val scope = rememberCoroutineScope()
    val others = listOf(AccentChoice.BLUE, AccentChoice.GOLDEN, AccentChoice.CHILI, AccentChoice.EMBER).filter { it != Palette.accentChoice }.map { if (Palette.dark) it.dark else it.light }
    val colours = listOf(Palette.accent, others[0], others[1], Palette.ink2)
    val limit = if (cacheMb == 0) "no limit" else cacheLabel(cacheMb)

    Column(Modifier.padding(start = 4.dp, top = 14.dp)) {
        Text(use?.let { sizeText(it.total) } ?: "…", fontFamily = Heading, fontSize = 40.sp, letterSpacing = (-1).sp, color = Palette.ink)
        Text("used by Spotifry", color = Palette.ink2, fontSize = 14.sp)
        Row(Modifier.padding(top = 14.dp).fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Palette.ink.copy(alpha = 0.08f)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (use != null) listOf(use.songs, use.replay, use.covers, use.database).forEachIndexed { i, bytes ->
                if (bytes > 0) Box(Modifier.weight(bytes.toFloat()).fillMaxHeight().background(colours[i]))
            }
        }
    }
    Section("What takes the space") {
        @Composable fun part(i: Int, title: String, subtitle: String, bytes: Long?) = Item(title, subtitle) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(colours[i]))
                Text(bytes?.let(::sizeText) ?: "…", Modifier.widthIn(min = 64.dp), textAlign = TextAlign.End, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Palette.ink)
            }
        }
        part(0, "Saved songs", "$savedCount ${if (savedCount == 1) "song" else "songs"} · ${shortPath(lib.saveFolder)}", use?.songs)
        part(1, "Played songs kept for replay", "limit $limit", use?.replay)
        part(2, "Covers", "of the saved songs", use?.covers)
        part(3, "Database", "playlists, history, settings", use?.database)
    }
    services.replay?.let { replay ->
        Section("Replay storage") {
            Item("Limit", "Most space for played songs. ∞ keeps them all.") {
                Segments(CACHE_MB.map(::cacheLabel), CACHE_MB.indexOf(cacheMb).coerceAtLeast(0)) { settings.setCacheMb(CACHE_MB[it]) }
            }
            Item("Clear played songs kept for replay", use?.let { sizeText(it.replay) } ?: "Counting…", onClick = { scope.launch(Dispatchers.IO) { replay.clear(); onChanged() } }) { Action("Clear") }
        }
    }
    Section("Backup") {
        Item("Save backup", "Playlists, history, trims and settings in one file.", onClick = flow::save) { Action("Save") }
        Item("Restore backup", "Brings a backup into this computer. A backup from the phone works too.", onClick = flow::choose) { Action("Restore") }
    }
}

@Composable
internal fun HistoryGroup(services: Services, onClearHistory: () -> Unit) {
    val lib = services.library
    val recent by lib.recentSearches.list.collectAsState()
    Section(null) {
        Item("Clear recent searches", if (recent.isEmpty()) "Nothing to clear" else "${recent.size} kept", onClick = if (recent.isEmpty()) null else lib.recentSearches::clear) { if (recent.isNotEmpty()) Action("Clear") }
        Item("Clear listening history", "Empties Recently played, Most played and your listening", danger = true, onClick = onClearHistory) { Action("Clear", danger = true) }
    }
}

@Composable
internal fun AboutGroup(services: Services) {
    Section(null) {
        UpdateRow(services)
        Item("Music from YouTube", "Through NewPipe Extractor $NEWPIPE_VERSION.")
        // the photos' licences ask for the photographer and the licence to be named
        Item("Home pictures", "Photos from Wikimedia Commons: morning by Jakub Hałun (CC BY-SA 4.0), afternoon by PattayaPatrol (CC BY-SA 4.0), evening by Martin St-Amant (CC BY-SA 3.0), night by Vincent Lock (CC BY 2.0).")
    }
}

internal fun checkedText(ms: Long) =
    if (ms == 0L) "Not checked yet" else "Checked " + java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", java.util.Locale.ENGLISH).format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))

/** The version, when GitHub was last asked, and Check now; Update downloads and installs the new version right here. */
@Composable
private fun UpdateRow(services: Services) {
    val updates = services.library.updates
    val available by updates.available.collectAsState()
    val checking by updates.checking.collectAsState()
    val checked by updates.lastChecked.collectAsState()
    val working by updates.progress.collectAsState()
    val next = available
    val step = working?.step
    Item("Spotifry", "Version $desktopVersion · " + when { working != null -> working!!.text; checking -> "Checking…"; next != null -> "${next.version} is out"; else -> checkedText(checked) }) {
        @Composable fun action(label: String, run: () -> Unit) = Text(label, color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { run() }.padding(horizontal = 6.dp, vertical = 4.dp))
        when {
            step == UpdateStep.Working -> {}
            step == UpdateStep.Done -> action(if (Platform.isWindows) "Install" else "Restart") { updates.finish(); services.quit() }
            next != null -> action("Update") { updates.install(next) }
            !checking -> action("Check now") { updates.check() }
        }
    }
}

@Composable
internal fun ChangeLogGroup(log: ChangeLog) {
    val entries by log.entries.collectAsState()
    val failed by log.failed.collectAsState()
    val loading by log.loading.collectAsState()
    LaunchedEffect(Unit) { log.load() }
    when {
        failed -> Text("Couldn't reach GitHub." + if (entries.isEmpty()) " Try again when you're online." else " Showing the last list.", color = Palette.ink2, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 14.dp))
        entries.isEmpty() && loading -> Text("Loading…", color = Palette.ink2, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 14.dp))
    }
    if (entries.isNotEmpty()) Section(null) {
        entries.forEach { e ->
            val line = Palette.line
            Row(Modifier.fillMaxWidth().drawBehind { drawLine(line, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f) }.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(e.version, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Palette.ink)
                    logDate(e.date)?.let { Text(it, fontSize = 13.sp, color = Palette.ink2) }
                    if (e.version == desktopVersion) Text("You have this", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.accent, modifier = Modifier.padding(top = 8.dp))
                }
                Text(e.notes.ifEmpty { "No notes." }, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, color = Palette.ink2)
            }
        }
    }
}

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
