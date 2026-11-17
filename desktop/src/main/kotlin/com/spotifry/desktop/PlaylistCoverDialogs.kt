package com.spotifry.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.CropBox
import com.spotifry.music.data.cropBox
import com.spotifry.music.data.db.SongEntity
import org.jetbrains.skia.Image as SkImage

/** Where the new cover comes from. [hasCustom] adds the way back to the default cover. */
@Composable
fun ChangeCoverDialog(hasCustom: Boolean, canPickSong: Boolean, onPicture: () -> Unit, onSongs: () -> Unit, onDefault: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, containerColor = Palette.surface,
    title = { Text("Change cover", fontFamily = Heading, fontSize = 22.sp) },
    text = {
        Column {
            CoverChoice(Icons.Picture, "Choose a picture", "A picture from this computer", true, onPicture)
            CoverChoice(Icons.Playlists, "Pick from this playlist's songs", if (canPickSong) "Use the cover of one of its songs" else "Add a song first", canPickSong, onSongs)
            if (hasCustom) CoverChoice(Icons.List, "Use default cover", "The covers of the first songs", true, onDefault)
        }
    },
    confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Palette.ink2) } },
)

@Composable
private fun CoverChoice(icon: ImageVector, title: String, sub: String, enabled: Boolean, onClick: () -> Unit) {
    val ink = if (enabled) Palette.ink else Palette.ink2.copy(alpha = .5f)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = ink)
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = ink)
            Text(sub, fontSize = 13.sp, color = Palette.ink2)
        }
    }
}

/** The playlist's songs as a grid of their covers: click one, then Use as cover. */
@Composable
fun SongCoverDialog(songs: List<SongEntity>, onPick: (SongEntity) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.surface,
        title = { Text("Pick a cover", fontFamily = Heading, fontSize = 22.sp) },
        text = {
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.width(460.dp).heightIn(max = 420.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(songs, key = { it.id }) { s ->
                    val on = s.id == chosen
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).clickable { chosen = s.id }.then(if (on) Modifier.border(3.dp, Palette.accent, RoundedCornerShape(10.dp)) else Modifier)) {
                        CoverFill(s.thumbnailUrl ?: s.thumbPath, Modifier.fillMaxSize())
                        if (on) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(Palette.accent), Alignment.Center) {
                            Icon(Icons.Check, null, Modifier.size(15.dp), tint = Palette.onAccent)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ songs.firstOrNull { it.id == chosen }?.let(onPick) }, enabled = chosen != null) { Text("Use as cover", color = if (chosen != null) Palette.accent else Palette.ink2) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Palette.ink2) } },
    )
}

/** Move (drag) and zoom (scroll) a picture under a square window; Use cover keeps what is inside it. */
@Composable
fun CropDialog(picture: SkImage, onDone: (CropBox) -> Unit, onDismiss: () -> Unit) {
    val bitmap = remember(picture) { picture.toComposeImageBitmap() }
    val density = LocalDensity.current
    var zoom by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val sideDp = 380.dp
    val side = with(density) { sideDp.toPx() }
    val base = side / minOf(picture.width, picture.height)
    // how far the picture may be dragged before the window would show empty space
    val lim = Offset(maxOf((picture.width * base * zoom - side) / 2f, 0f), maxOf((picture.height * base * zoom - side) / 2f, 0f))
    val at = Offset(offset.x.coerceIn(-lim.x, lim.x), offset.y.coerceIn(-lim.y, lim.y))
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.surface,
        title = { Text("Move and zoom", fontFamily = Heading, fontSize = 22.sp) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(sideDp).clip(RoundedCornerShape(14.dp)).background(Palette.high)
                        .pointerInput(Unit) { detectDragGestures { c, d -> c.consume(); offset = Offset(offset.x + d.x, offset.y + d.y) } }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    if (e.type == PointerEventType.Scroll) { zoom = (zoom * (1f - e.changes.first().scrollDelta.y * .1f)).coerceIn(1f, 5f); e.changes.forEach { it.consume() } }
                                }
                            }
                        },
                    Alignment.Center,
                ) {
                    Image(
                        bitmap, "Your picture",
                        Modifier.requiredSize(with(density) { (picture.width * base * zoom).toDp() }, with(density) { (picture.height * base * zoom).toDp() })
                            .graphicsLayer { translationX = at.x; translationY = at.y },
                    )
                }
                Text("Drag to move, scroll to zoom.", color = Palette.ink2, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = {
            TextButton({
                val panX = if (lim.x > 0f) -at.x / lim.x else 0f
                val panY = if (lim.y > 0f) -at.y / lim.y else 0f
                onDone(cropBox(picture.width, picture.height, zoom, panX, panY))
            }) { Text("Use cover", color = Palette.accent) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Palette.ink2) } },
    )
}
