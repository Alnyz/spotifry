package com.spotifry.desktop

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
private class JumpItem(val group: String, val title: String, val hint: String = "", val run: () -> Unit)

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
    var focused by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf(0) }
    val q = query.trim()
    // the hint and the typed text share one style and line height, so the cursor sits on the text's own line
    val field = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 18.sp)

    fun done(block: () -> Unit) = { onQuery(""); leave(); block() }
    val items = remember(q, saved, recent, lists) {
        val has = { s: String -> q.isEmpty() || s.contains(q, ignoreCase = true) }
        buildList {
            listOf("Home" to Route.Home, "Saved" to Route.Saved, "Playlists" to Route.Playlists, "Settings" to Route.Settings).filter { has(it.first) }
                .forEach { (name, r) -> add(JumpItem("Go to", name) { done { goPage(r) }() }) }
            if (has("Now playing")) add(JumpItem("Go to", "Now playing") { done { goNowPlaying() }() })
            if (q.isNotEmpty()) {
                lists.filter { has(it.name) }.take(4).forEach { p -> add(JumpItem("Playlists", p.name, "${p.songCount} songs") { done { goPage(Route.Playlist(p.id)) }() }) }
                val own = (saved + recent.map { it }).distinctBy(SongEntity::id).filter { has(it.title) || has(it.artist) }.take(5).map(SongEntity::toSong)
                own.forEach { s -> add(JumpItem("Your songs", s.title, s.artist) { done { services.player.playList(listOf(s), 0, "Search") }() }) }
                add(JumpItem("Search", "Search for “$q”") { leave(); onSearch() })
            }
        }
    }
    LaunchedEffect(items.size, q) { sel = 0 }
    fun pick() = items.getOrNull(sel)?.run?.invoke()

    Box {
        Row(
            Modifier.width(width).height(34.dp).clip(CircleShape).background(Palette.ink.copy(alpha = 0.04f)).border(1.dp, Palette.ink.copy(alpha = if (focused) 0.3f else 0.14f), CircleShape).padding(start = 14.dp, end = 8.dp),
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
            Text(jumpShortcut, fontSize = 11.sp, color = Palette.ink.copy(alpha = 0.62f), modifier = Modifier.border(1.dp, Palette.ink.copy(alpha = 0.14f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        if (focused && items.isNotEmpty()) Popup(popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                IntOffset(anchorBounds.right - popupContentSize.width, anchorBounds.bottom + 8)
        }) {
            Column(Modifier.width(360.dp).clip(RoundedCornerShape(14.dp)).background(Palette.surface).border(1.dp, Palette.ink.copy(alpha = 0.14f), RoundedCornerShape(14.dp)).padding(8.dp)) {
                var last = ""
                items.forEachIndexed { i, item ->
                    if (item.group != last) { last = item.group; Kicker(item.group, Modifier.padding(start = 10.dp, top = if (i == 0) 4.dp else 12.dp, bottom = 4.dp)) }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (i == sel) Palette.ink.copy(alpha = 0.09f) else androidx.compose.ui.graphics.Color.Transparent).clickable { item.run() }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.title, fontFamily = Serif, fontSize = 15.sp, color = Palette.ink, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.weight(1f))
                        if (item.hint.isNotEmpty()) Text(item.hint, fontSize = 12.sp, color = Palette.ink2, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
}
