package com.spotifry.desktop

import androidx.compose.material3.Icon
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.spotifry.music.data.db.SongEntity

val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

/** What the shortcut is called on this system: ⌘ K on a Mac, Ctrl K elsewhere (the Windows key is reserved by Windows, so it is not used). */
val jumpShortcut = if (isMac) "⌘ K" else "Ctrl K"

/** One line in the jump list: what it is, a small hint on the right, and what happens when it is picked. */
private class JumpItem(val group: String, val title: String, val hint: String = "", val icon: androidx.compose.ui.graphics.vector.ImageVector? = null, val cover: String? = null, val run: () -> Unit)

/**
 * The bar in the top row. Empty, it lists the places to go; typing narrows them and adds your playlists and the songs you
 * already have; the last line always searches for the text. Up and Down move, Enter picks, Esc leaves.
 */
@Composable
fun JumpBar(
    services: Services, query: String, onQuery: (String) -> Unit, focus: FocusRequester, leave: () -> Unit, width: Dp,
    goPage: (Route) -> Unit, goNowPlaying: () -> Unit, onSearch: () -> Unit,
) {
    val lib = services.library
    val saved by lib.db.songs().observeSaved().collectAsState(emptyList())
    val recent by lib.db.history().observeRecent().collectAsState(emptyList())
    val lists by lib.db.playlists().observePlaylists().collectAsState(emptyList())
    val files by lib.musicFolder.songs.collectAsState()
    var focused by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf(0) }
    val q = query.trim()
    // the hint and the typed text share one style and line height, so the cursor sits on the text's own line
    val field = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 18.sp)

    fun done(block: () -> Unit) = { onQuery(""); leave(); block() }
    val items = remember(q, saved, recent, lists, files) {
        val has = { s: String -> q.isEmpty() || s.contains(q, ignoreCase = true) }
        buildList {
            listOf(Triple("Home", Route.Home, Icons.Home), Triple("Saved", Route.Saved, Icons.Saved), Triple("Playlists", Route.Playlists, Icons.Playlists), Triple("Settings", Route.Settings, Icons.Settings))
                .filter { has(it.first) }.forEach { (name, r, icon) -> add(JumpItem("Go to", name, icon = icon) { done { goPage(r) }() }) }
            if (has("Now playing")) add(JumpItem("Go to", "Now playing", icon = Icons.Wave) { done { goNowPlaying() }() })
            if (q.isNotEmpty()) {
                lists.filter { has(it.name) }.take(4).forEach { p -> add(JumpItem("Playlists", p.name, "${p.songCount} songs", Icons.Playlists) { done { goPage(Route.Playlist(p.id)) }() }) }
                val own = (saved + recent.map { it }).distinctBy(SongEntity::id).filter { has(it.title) || has(it.artist) }.map(SongEntity::toSong)
                    .plus(com.spotifry.music.data.localMatches(files, q, 5)).distinctBy { it.id }.take(5)
                own.forEach { s -> add(JumpItem("Your songs", s.title, s.artist, cover = s.thumbnailUrl ?: s.localThumbPath ?: "") { done { services.player.playList(listOf(s), 0, "Search") }() }) }
                add(JumpItem("Search", "Search for “$q”", icon = Icons.Search) { leave(); onSearch() })
            }
        }
    }
    LaunchedEffect(items.size, q) { sel = 0 }
    fun pick() = items.getOrNull(sel)?.run?.invoke()

    Box {
        Row(
            Modifier.width(width).height(34.dp).clip(CircleShape).glassFill().border(1.dp, Palette.ink.copy(alpha = if (focused) 0.35f else 0.2f), CircleShape).padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Jump to anything", style = field, color = Palette.ink.copy(alpha = 0.4f), maxLines = 1)
                BasicTextField(
                    query, onQuery, singleLine = true, textStyle = field.copy(color = Palette.ink), cursorBrush = SolidColor(Palette.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent {
                        if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (it.key) {
                            Key.DirectionDown -> { sel = (sel + 1).coerceAtMost((items.size - 1).coerceAtLeast(0)); true }
                            Key.DirectionUp -> { sel = (sel - 1).coerceAtLeast(0); true }
                            Key.Enter, Key.NumPadEnter -> { pick(); true }
                            Key.Escape -> { onQuery(""); leave(); true }
                            else -> false
                        }
                    },
                )
            }
            // the shortcut as quiet text at the end, no box
            Text(jumpShortcut, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Palette.ink.copy(alpha = 0.5f), modifier = Modifier.padding(end = 8.dp))
        }
        if (focused && items.isNotEmpty()) Popup(popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                IntOffset(anchorBounds.right - popupContentSize.width, anchorBounds.bottom + 8)
        }) {
            // dark glass like the player bar (a popup can't blur what is under it), the picked line in the accent like the top row
            val shape = RoundedCornerShape(20.dp)
            val glass = androidx.compose.ui.graphics.lerp(Palette.bg, Palette.glow ?: Palette.accent, 0.06f).copy(alpha = 0.97f)
            Column(Modifier.width(380.dp).shadow(28.dp, shape).clip(shape).background(glass).border(1.dp, Palette.ink.copy(alpha = 0.1f), shape).padding(10.dp)) {
                var last = ""
                items.forEachIndexed { i, item ->
                    if (item.group != last) { last = item.group; Kicker(item.group, Modifier.padding(start = 12.dp, top = if (i == 0) 6.dp else 14.dp, bottom = 6.dp)) }
                    val on = i == sel
                    val tint = if (on) Palette.accent else Palette.ink
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) Palette.accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { item.run() }.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (item.cover != null) Cover(item.cover.ifEmpty { null }, 34.dp, RoundedCornerShape(8.dp))
                        else Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Palette.accent.copy(alpha = 0.18f) else Palette.ink.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                            item.icon?.let { Icon(it, null, Modifier.size(18.dp), tint = tint.copy(alpha = if (on) 1f else 0.75f)) }
                        }
                        Text(item.title, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 15.sp, color = tint, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (item.hint.isNotEmpty()) Text(item.hint, fontSize = 12.sp, color = Palette.ink2, maxLines = 1, modifier = Modifier.padding(end = 4.dp))
                    }
                }
            }
        }
    }
}
