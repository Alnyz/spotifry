package com.spotifry.music.ui.playlists

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.cropBox
import com.spotifry.music.data.cropped
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.CoverArt
import com.spotifry.music.ui.components.Pill
import kotlin.math.max
import kotlin.math.min

/** The pencil: Rename, or Change cover. */
@Composable
fun EditPlaylistSheet(onRename: () -> Unit, onChangeCover: () -> Unit, onClose: () -> Unit) = SheetFrame(onClose) {
    SheetRow(Icons.Edit, "Rename", null, onRename)
    SheetRow(Icons.Picture, "Change cover", null, onChangeCover)
}

/** Where the new cover comes from. [hasCustom] adds the way back to the default cover. */
@Composable
fun ChangeCoverSheet(hasCustom: Boolean, canPickSong: Boolean, onGallery: () -> Unit, onSongs: () -> Unit, onDefault: () -> Unit, onClose: () -> Unit) = SheetFrame(onClose) {
    Text("Change cover", Modifier.padding(start = 22.dp, bottom = 8.dp), style = MaterialTheme.typography.headlineSmall)
    SheetRow(Icons.Picture, "Choose from gallery", "A picture from your phone", onGallery)
    SheetRow(Icons.Note, "Pick from this playlist's songs", if (canPickSong) "Use the cover of one of its songs" else "Add a song first", onSongs, enabled = canPickSong)
    if (hasCustom) SheetRow(Icons.List, "Use default cover", "The covers of the first songs", onDefault)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SheetFrame(onClose: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) { Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) { content() } }
}

@Composable
private fun SheetRow(icon: ImageVector, title: String, sub: String?, onClick: () -> Unit, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 22.dp, vertical = 14.dp),
        Arrangement.spacedBy(16.dp), Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = .5f))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = .5f))
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}

/** The playlist's songs as a grid of their covers: tap one, then "Use as cover". */
@Composable
fun SongCoverPicker(songs: List<SongEntity>, onPick: (SongEntity) -> Unit, onBack: () -> Unit, bottomInset: Dp, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    var chosen by remember { mutableStateOf<String?>(null) }
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().background(scheme.background).statusBarsPadding()) {
        Row(Modifier.padding(start = 4.dp, end = 20.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack, Modifier.size(48.dp)) { Icon(Icons.Back, "Back") }
            Text("Pick a cover", style = MaterialTheme.typography.titleLarge)
        }
        Text("Choose the cover of one of the songs.", Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(songs, key = { it.id }) { s ->
                val on = s.id == chosen
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).clickable { chosen = s.id }.then(if (on) Modifier.border(3.dp, scheme.primary, RoundedCornerShape(12.dp)) else Modifier)) {
                    CoverArt(s.id, s.thumbnailUrl, s.thumbPath, Modifier.fillMaxSize(), large = false)
                    if (on) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(scheme.primary), Alignment.Center) {
                        Icon(Icons.Check, null, Modifier.size(16.dp), tint = scheme.onPrimary)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp + bottomInset), Alignment.Center) {
            Pill(null, "Use as cover", if (chosen != null) scheme.primary else scheme.surfaceContainerHigh, if (chosen != null) scheme.onPrimary else scheme.onSurfaceVariant, { songs.firstOrNull { it.id == chosen }?.let(onPick) })
        }
    }
}

/** Move and zoom a picture under a square window; Use cover keeps what is inside the window. */
@Composable
fun CropScreen(picture: Bitmap, onDone: (Bitmap) -> Unit, onCancel: () -> Unit, bottomInset: Dp, modifier: Modifier = Modifier) {
    BackHandler(onBack = onCancel)
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Column(modifier.fillMaxSize().background(Color.Black).statusBarsPadding().padding(bottom = bottomInset)) {
        Row(Modifier.padding(start = 4.dp, end = 20.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onCancel, Modifier.size(48.dp)) { Icon(Icons.Close, "Cancel", tint = Color.White) }
            Text("Move and zoom", style = MaterialTheme.typography.titleLarge, color = Color.White)
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(0.dp)), Alignment.Center) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val side = with(density) { (minOf(maxWidth, maxHeight) - 48.dp).toPx() }
                val base = side / min(picture.width, picture.height)
                // how far the picture may be dragged before the window would show empty space
                fun limit(z: Float, w: Int, h: Int) = Offset(max((w * base * z - side) / 2f, 0f), max((h * base * z - side) / 2f, 0f))
                val lim = limit(zoom, picture.width, picture.height)
                val at = Offset(offset.x.coerceIn(-lim.x, lim.x), offset.y.coerceIn(-lim.y, lim.y))
                Box(
                    Modifier.fillMaxSize().pointerInput(picture) {
                        detectTransformGestures { _, pan, z, _ -> zoom = (zoom * z).coerceIn(1f, 5f); offset += pan }
                    },
                    Alignment.Center,
                ) {
                    Image(
                        picture.asImageBitmap(), "Your picture",
                        Modifier.requiredSize(with(density) { (picture.width * base * zoom).toDp() }, with(density) { (picture.height * base * zoom).toDp() })
                            .graphicsLayer { translationX = at.x; translationY = at.y },
                    )
                    // everything outside the square window is dimmed
                    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                        drawRect(Color.Black.copy(alpha = .62f))
                        val tl = Offset((size.width - side) / 2f, (size.height - side) / 2f)
                        drawRoundRect(Color.Black, tl, Size(side, side), CornerRadius(with(density) { 14.dp.toPx() }), blendMode = BlendMode.Clear)
                    }
                    Box(Modifier.requiredSize(with(density) { side.toDp() }).border(2.dp, Color.White.copy(alpha = .8f), RoundedCornerShape(14.dp)))
                }
                // the window's place on the picture, from how far it was dragged
                val keep = {
                    val panX = if (lim.x > 0f) -at.x / lim.x else 0f
                    val panY = if (lim.y > 0f) -at.y / lim.y else 0f
                    picture.cropped(cropBox(picture.width, picture.height, zoom, panX, panY))
                }
                Box(Modifier.fillMaxSize().padding(bottom = 16.dp), Alignment.BottomCenter) {
                    Pill(null, "Use cover", scheme.primary, scheme.onPrimary, { onDone(keep()) })
                }
            }
        }
    }
}
