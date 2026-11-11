package com.spotifry.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.Song
import kotlinx.coroutines.launch

/** Page heading with a quiet line under it and the page's buttons on the right. */
@Composable
fun PageHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = Serif, fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = (-1).sp, color = Palette.ink)
            if (subtitle != null) Text(subtitle, color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

@Composable
fun Pill(text: String, icon: ImageVector? = null, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(if (primary) Palette.ink else Color.Transparent)
            .then(if (primary) Modifier else Modifier.border(1.dp, Palette.ink.copy(alpha = 0.2f), RoundedCornerShape(22.dp))).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = if (primary) Palette.bg else Palette.ink)
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (primary) Palette.bg else Palette.ink)
    }
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (on) Palette.accent.copy(alpha = 0.18f) else Palette.high).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, color = if (on) Palette.accent else Palette.ink2) }
}

@Composable
fun SectionTitle(text: String) = Column(Modifier.padding(top = 28.dp, bottom = 8.dp)) {
    Text(text, fontFamily = Serif, fontSize = 22.sp, color = Palette.ink, modifier = Modifier.padding(bottom = 8.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.ink.copy(alpha = 0.14f)))
}

@Composable
fun EmptyState(title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontFamily = Serif, fontSize = 24.sp, color = Palette.ink)
        Text(text, color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Up to four covers in a square, for a playlist. */
@Composable
fun Mosaic(covers: List<String?>, size: Dp, radius: Dp = 14.dp) {
    val shape = RoundedCornerShape(radius)
    Box(Modifier.size(size).clip(shape).background(Palette.high)) {
        val tile = size / 2
        if (covers.size <= 1) Cover(covers.firstOrNull(), size, shape, large = true)
        else Column {
            for (r in 0..1) Row { for (c in 0..1) Cover(covers.getOrNull(r * 2 + c), tile, RoundedCornerShape(0.dp)) }
        }
    }
}

/** A cover with the name under it, the way Home and Playlists show a song or a playlist. */
@Composable
fun CoverCard(title: String, subtitle: String, cover: @Composable (Dp) -> Unit, width: Dp = 180.dp, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hot by src.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hot) 1.04f else 1f, tween(200))
    // no grey box: the cover lifts a little and the name warms up
    Column(Modifier.width(width).hoverable(src).clickable(src, null, onClick = onClick)) {
        Box(Modifier.graphicsLayer { scaleX = lift; scaleY = lift }) { cover(width) }
        Text(title, fontFamily = Serif, fontSize = 17.sp, maxLines = 1, color = if (hot) Palette.accent else Palette.ink, modifier = Modifier.padding(top = 10.dp))
        Text(subtitle, color = Palette.ink2, fontSize = 13.sp, maxLines = 1)
    }
}

/** A name typed into a small window: new playlist, rename. */
@Composable
fun NameDialog(title: String, confirm: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.surface,
        title = { Text(title, fontFamily = Serif, fontSize = 22.sp) },
        text = {
            OutlinedTextField(
                text, { text = it }, singleLine = true, label = { Text("Name") }, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.onEnter { if (text.isNotBlank()) onDone(text.trim()) },
            )
        },
        confirmButton = { TextButton({ if (text.isNotBlank()) onDone(text.trim()) }) { Text(confirm, color = Palette.accent) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Palette.ink2) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onDone: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, containerColor = Palette.surface, title = { Text(title, fontFamily = Serif, fontSize = 22.sp) }, text = { Text(text, color = Palette.ink2) },
    confirmButton = { TextButton(onDone) { Text(confirm, color = Palette.danger) } },
    dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Palette.ink2) } },
)

/**
 * A song in a list: number, cover, name, length, and a menu. [extra] adds entries to the menu (Remove from this playlist).
 */
@Composable
fun SongLine(services: Services, number: Int?, s: Song, playing: Boolean, saved: Boolean = false, extra: @Composable (close: () -> Unit) -> Unit = {}, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (number != null) Text(number.toString(), fontFamily = Serif, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = Palette.ink.copy(alpha = 0.4f), fontSize = 14.sp, modifier = Modifier.width(24.dp))
        Cover(s.thumbnailUrl ?: s.localThumbPath, 44.dp, RoundedCornerShape(6.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s.title, fontFamily = Serif, fontSize = 18.sp, maxLines = 1, color = if (playing) Palette.accent else Palette.ink, modifier = Modifier.weight(1f, fill = false))
                if (saved) Icon(Icons.Saved, "Saved on this computer", Modifier.size(15.dp), tint = Palette.accent)
            }
            Text(s.artist, color = Palette.ink2, fontSize = 13.sp, maxLines = 1)
        }
        Text(clock(s.durationSec * 1000), color = Palette.ink2, fontSize = 13.sp)
        Box {
            Box(Modifier.size(32.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) { Icon(Icons.More, "More", Modifier.size(20.dp), tint = Palette.ink2) }
            if (menu) SongMenu(services, s, { menu = false }, extra)
        }
    }
}

@Composable
fun SongMenu(services: Services, song: Song, close: () -> Unit, extra: @Composable (() -> Unit) -> Unit) {
    val lib = services.library
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    LaunchedEffect(song.id) { saved = lib.db.songs().get(song.id)?.isSaved == true }
    val lists by lib.db.playlists().observePlaylists().collectAsState(emptyList())
    val state by services.player.state.collectAsState()
    DropdownMenu(true, close, containerColor = Palette.surface) {
        if (!choosing) {
            // the playing song is already there, and a song already waiting is moved or left alone, never listed twice
            val playingThis = state.current?.id == song.id
            val waiting = (state.currentIndex + 1 until state.queue.size).filter { state.queue[it].id == song.id }
            if (!playingThis) {
                DropdownMenuItem({ Text("Play next") }, onClick = {
                    if (state.queue.isEmpty()) services.player.playList(listOf(song), 0)
                    else { waiting.sortedDescending().forEach { services.player.remove(it, song.id) }; services.player.insert(state.currentIndex + 1, song, null) }
                    close()
                })
                if (waiting.isEmpty()) DropdownMenuItem({ Text("Add to queue") }, onClick = {
                    if (state.queue.isEmpty()) services.player.playList(listOf(song), 0) else services.player.insert(state.queue.size, song, null)
                    close()
                })
            }
            DropdownMenuItem({ Text(if (saved) "Remove from saved" else "Save on this computer") }, onClick = {
                scope.launch { if (saved) lib.downloads.remove(song.id) else lib.downloads.save(song) }
                close()
            })
            DropdownMenuItem({ Text("Add to a playlist") }, onClick = { choosing = true })
            extra(close)
        } else {
            DropdownMenuItem({ Text("New playlist…", color = Palette.accent) }, onClick = { naming = true })
            lists.forEach { p ->
                DropdownMenuItem({ Text(p.name) }, onClick = {
                    scope.launch { lib.db.songs().upsert(song.toEntity()); lib.db.playlists().addSong(p.id, song.id) }
                    close()
                })
            }
        }
    }
    if (naming) NameDialog("New playlist", "Create", "", { naming = false }) { name ->
        scope.launch { val id = lib.db.playlists().create(name); lib.db.songs().upsert(song.toEntity()); lib.db.playlists().addSong(id, song.id) }
        naming = false; close()
    }
}
