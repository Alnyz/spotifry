package com.spotifry.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val GROUP_KEY = "desktop_settings_group"

/** Below this width the list and the options no longer fit side by side. */
private val SPLIT_WIDTH = 700.dp

private enum class SettingsGroup(val title: String, val icon: ImageVector) {
    Sound("Sound & playback", Icons.Tune), Look("Look", Icons.Contrast), Music("Music on this computer", Icons.Computer), Lyrics("Lyrics", Icons.Lyrics),
    Storage("Storage & backup", Icons.Database), History("History", Icons.History),
    About("About", Icons.Info), Changes("Change log", Icons.Doc);

    /** The list shows a divider above these. */
    val startsBlock get() = this == Storage || this == About
}

/** True while the last thing the user did was press a key, so a focus outline shows for the keyboard and not after a click. */
private var viaKeys by mutableStateOf(false)

@Composable
fun SettingsPage(services: Services, flow: BackupFlow, onEqualizer: () -> Unit = {}, modifier: Modifier = Modifier) {
    val lib = services.library
    val settings = lib.settings
    val name by settings.name.collectAsState()
    val theme by settings.theme.collectAsState()
    val accent by settings.accent.collectAsState()
    val fade by settings.fadeSeconds.collectAsState()
    val eqOn by settings.eqOn.collectAsState()
    val eqPreset by settings.eqPreset.collectAsState()
    val hide by settings.hideNonMusic.collectAsState()
    val lyricsOff by settings.lyricsOff.collectAsState()
    val checked by lib.updates.lastChecked.collectAsState()
    var selected by remember { mutableStateOf(SettingsGroup.entries.firstOrNull { it.name == lib.prefs.getString(GROUP_KEY, null) } ?: SettingsGroup.Sound) }
    var opened by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var pickingColour by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(0) }
    val use by produceState<StorageUse?>(null, changed) { value = withContext(Dispatchers.IO) { measureStorage(lib.dir) } }
    val scope = rememberCoroutineScope()

    if (pickingColour) ColourDialog(Color(settings.accentColor.value), { pickingColour = false }) { settings.setAccent(AccentChoice.CUSTOM.name, it.toArgb()); pickingColour = false }
    if (editName) NameDialog("Your name", "Save", name, { editName = false }) { settings.setName(it); editName = false }
    if (clearing) ConfirmDialog("Clear listening history?", "Empties Recently played, Most played and your listening. Saved songs and playlists stay.", "Clear", { clearing = false }) {
        scope.launch { lib.db.history().clear(); lib.db.plays().clear(); lib.db.listens().clear() }; clearing = false
    }

    fun summary(g: SettingsGroup) = when (g) {
        SettingsGroup.Sound -> "Crossfade ${if (fade == 0) "off" else "$fade s"}, Equalizer: ${if (eqOn) eqPreset?.removePrefix(com.spotifry.music.player.AUTOEQ_PREFIX) ?: "Custom" else "Off"}"
        SettingsGroup.Look -> "${when (theme) { ThemeMode.PHONE -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" }}, ${AccentChoice.of(accent).label} accent"
        SettingsGroup.Music -> if (hide) "Hiding clips and voice notes" else "Showing all audio"
        SettingsGroup.Lyrics -> "${com.spotifry.music.data.Lyrics.SOURCES.count { it !in lyricsOff }} of ${com.spotifry.music.data.Lyrics.SOURCES.size} sources on"
        SettingsGroup.Storage -> use?.let { "${sizeText(it.total)} used by Spotifry" } ?: "Counting…"
        SettingsGroup.History -> "Searches and listening history"
        SettingsGroup.About -> "Version $desktopVersion · " + checkedText(checked).replaceFirstChar { it.lowercase() }
        SettingsGroup.Changes -> "What's new in each version"
    }
    fun pick(g: SettingsGroup) { selected = g; opened = true; lib.prefs.edit().putString(GROUP_KEY, g.name).apply() }

    val detail: @Composable () -> Unit = {
        key(selected) {
            when (selected) {
                SettingsGroup.Sound -> SoundGroup(services, onEqualizer)
                SettingsGroup.Look -> LookGroup(services, { editName = true }, { pickingColour = true })
                SettingsGroup.Music -> MusicGroup(services)
                SettingsGroup.Lyrics -> LyricsGroup(settings)
                SettingsGroup.Storage -> StorageGroup(services, flow, use) { changed++ }
                SettingsGroup.History -> HistoryGroup(services) { clearing = true }
                SettingsGroup.About -> AboutGroup(services)
                SettingsGroup.Changes -> ChangeLogGroup(lib.changeLog)
            }
        }
    }

    BoxWithConstraints(
        modifier.onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) viaKeys = true; false }
            .pointerInput(Unit) { awaitPointerEventScope { while (true) { if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) viaKeys = false } } },
    ) {
        val split = maxWidth >= SPLIT_WIDTH
        Column(Modifier.fillMaxSize()) {
            PageHeader("Settings")
            Spacer(Modifier.height(24.dp))
            if (split) Row(Modifier.fillMaxSize()) {
                GroupList(selected, summary = ::summary, split = true, onPick = ::pick, modifier = Modifier.width(300.dp))
                Spacer(Modifier.width(56.dp))
                Detail(selected, back = null, modifier = Modifier.weight(1f).widthIn(max = 720.dp), detail)
            } else if (opened) Detail(selected, back = { opened = false }, modifier = Modifier.fillMaxSize(), detail)
            else GroupList(selected, summary = ::summary, split = false, onPick = ::pick, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun GroupList(selected: SettingsGroup, summary: (SettingsGroup) -> String, split: Boolean, onPick: (SettingsGroup) -> Unit, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp + LocalBottomInset.current)) {
        SettingsGroup.entries.forEach { g ->
            if (g.startsBlock) Box(Modifier.padding(vertical = 10.dp, horizontal = 14.dp).fillMaxWidth().height(1.dp).background(Palette.line))
            GroupItem(g, summary(g), selected = split && g == selected, onPick = { onPick(g) }, selectOnFocus = split)
        }
    }
}

@Composable
private fun GroupItem(g: SettingsGroup, summary: String, selected: Boolean, selectOnFocus: Boolean, onPick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hot by src.collectIsHoveredAsState()
    val focus = LocalFocusManager.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
            .background(if (selected) Palette.high else if (hot) Palette.ink.copy(alpha = 0.05f) else Color.Transparent)
            .keyRing(src, shape)
            .onFocusChanged { if (it.isFocused && viaKeys && selectOnFocus) onPick() }
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) false
                else when (it.key) {
                    Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
                    Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
                    else -> false
                }
            }
            .hoverable(src).clickable(src, null, onClick = onPick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(g.icon, null, Modifier.size(20.dp), tint = if (selected) Palette.accent else Palette.ink2)
        Column(Modifier.weight(1f)) {
            Text(g.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(summary, fontSize = 13.sp, color = Palette.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** An outline in the accent colour while the keyboard has focus here. */
@Composable
internal fun Modifier.keyRing(src: MutableInteractionSource, shape: androidx.compose.ui.graphics.Shape): Modifier {
    val focused by src.collectIsFocusedAsState()
    return if (focused && viaKeys) border(2.dp, Palette.accent, shape) else this
}

@Composable
private fun Detail(group: SettingsGroup, back: (() -> Unit)?, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.verticalScroll(key(group) { rememberScrollState() }).padding(bottom = 24.dp + LocalBottomInset.current)) {
        if (back != null) {
            val src = remember { MutableInteractionSource() }
            val shape = RoundedCornerShape(8.dp)
            Row(Modifier.padding(bottom = 10.dp).clip(shape).keyRing(src, shape).clickable(src, null, onClick = back).padding(end = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Back, null, Modifier.size(22.dp), tint = Palette.accent)
                Text("Back", color = Palette.accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
        Text(group.title, fontFamily = Heading, fontSize = 30.sp, letterSpacing = (-0.5).sp, color = Palette.ink, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}
