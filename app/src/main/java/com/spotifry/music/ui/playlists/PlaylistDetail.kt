package com.spotifry.music.ui.playlists

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.data.PlaylistOrder
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.matches
import com.spotifry.music.data.source.Song
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.Cover
import com.spotifry.music.ui.components.EmptyState
import com.spotifry.music.ui.components.LoadingBox
import com.spotifry.music.ui.components.LoadingRow
import com.spotifry.music.ui.components.LoadingShapes
import com.spotifry.music.ui.components.PlayButtons
import com.spotifry.music.ui.components.ScreenHeader
import com.spotifry.music.ui.components.SongRow
import com.spotifry.music.ui.components.countsLabel
import com.spotifry.music.ui.home.SearchField
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.cropBox
import com.spotifry.music.data.cropped
import com.spotifry.music.data.decodePicked
import com.spotifry.music.ui.components.CoverMosaic
import com.spotifry.music.data.db.look
import com.spotifry.music.ui.components.LocalImageClient
import com.spotifry.music.ui.components.coverBitmap
import com.spotifry.music.ui.components.playlistCoverPath
import com.spotifry.music.ui.components.tintOf
import com.spotifry.music.ui.home.Bodoni
import com.spotifry.music.ui.home.RoundButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PlaylistDetailScreen(vm: PlaylistsViewModel, id: Long, onBack: () -> Unit, onMore: (Song, Long) -> Unit, bottomInset: Dp, modifier: Modifier = Modifier, onShare: (String, List<Song>) -> Unit = { _, _ -> }) {
    val all by vm.playlists.collectAsStateWithLifecycle()
    val playlist = all.firstOrNull { it.id == id }
    val covers by vm.covers.collectAsStateWithLifecycle()
    val coverPath = playlistCoverPath(playlist)
    var order by remember(id) { mutableStateOf(vm.orderOf(id)) }
    // keyed on the order so a new choice swaps the list at once
    val songs by remember(id, order) { vm.songsOf(id, order) }.collectAsStateWithLifecycle(null)
    var query by remember(id) { mutableStateOf("") }
    var editing by remember(id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var pencil by remember { mutableStateOf(false) }
    var coverSheet by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var cropping by remember { mutableStateOf<Bitmap?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var nameSheet by remember { mutableStateOf(false) }
    val look = playlist?.look() ?: com.spotifry.music.data.NameLook()
    val logoFile = vm.nameLogoFile(look.logoFile)
    var deleting by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val name = playlist?.name.orEmpty()
    val client = LocalImageClient.current
    val files = LocalContext.current.contentResolver

    /** Saves the new cover (null is the default one) and offers Undo; the old picture is deleted once Undo is no longer possible. */
    suspend fun changeCover(square: Bitmap?) {
        val change = vm.setCover(id, square)
        var undone = false
        try {
            undone = snackbar.showSnackbar(if (square == null) "Back to the default cover" else "Cover changed", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed
        } finally {
            withContext(NonCancellable) { if (undone) vm.undoCover(id, change) else vm.keepCover(change) }
        }
    }
    // a document picker, not the photo picker: that one doesn't list SVG files
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { if (!vm.setNameLogo(id, files, uri)) snackbar.showSnackbar("Couldn't use that picture") }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val picture = decodePicked(files, uri)
            if (picture != null) cropping = picture else snackbar.showSnackbar("Couldn't open that picture")
        }
    }

    Box(modifier.fillMaxSize()) {
        val list = songs
        if (editing && list != null) {
            // always edits your own order, whatever order is showing
            val own by remember(id) { vm.songsOf(id) }.collectAsStateWithLifecycle(null)
            own?.let { OrderEditor(it, bottomInset) { ids -> vm.saveOrder(id, ids); order = PlaylistOrder.CUSTOM; editing = false } }
        } else PlaylistDetailContent(
            name, list.orEmpty(), query, { query = it }, order,
            onOrder = { vm.setOrder(id, it); order = it },
            onEditOrder = { query = ""; editing = true },
            onMixUp = {
                scope.launch {
                    val old = vm.mixUp(id)
                    order = PlaylistOrder.CUSTOM
                    if (snackbar.showSnackbar("Order mixed up", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) vm.saveOrder(id, old)
                }
            },
            onPlay = { l, i -> vm.play(l, i, name) }, onShuffle = { vm.shuffle(it, name) }, onMore = { onMore(it, id) }, onMenu = { menu = true }, onBack = onBack,
            bottomInset = bottomInset, subtitle = playlist?.let(::countsLabel), loading = list == null,
            coverSongs = covers[id] ?: list.orEmpty().take(4), coverPath = coverPath, onEdit = { pencil = true },
            look = look, logo = logoFile,
        ) {
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Share") }, enabled = !list.isNullOrEmpty(), onClick = { menu = false; onShare(name, list.orEmpty().map { it.toSong() }) })
                DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { menu = false; deleting = true })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = (bottomInset - com.spotifry.music.ui.FadeBarSpec.FADE).coerceAtLeast(0.dp) + 8.dp))
        if (picking) SongCoverPicker(songs.orEmpty(), { s ->
            scope.launch {
                val bmp = coverBitmap(client, s.thumbnailUrl, s.thumbPath, files)
                if (bmp == null) snackbar.showSnackbar("Couldn't load that cover") else {
                    picking = false
                    changeCover(bmp.cropped(cropBox(bmp.width, bmp.height, 1f, 0f, 0f)))
                }
            }
        }, { picking = false }, bottomInset)
        cropping?.let { pic -> CropScreen(pic, { square -> cropping = null; scope.launch { changeCover(square) } }, { cropping = null }, bottomInset) }
    }
    if (pencil) EditPlaylistSheet({ pencil = false; renaming = true }, { pencil = false; coverSheet = true }, { pencil = false; nameSheet = true }, { pencil = false })
    if (nameSheet) {
        val coverSongs = covers[id] ?: songs.orEmpty().take(4)
        val tint = rememberCoverTint(coverSongs, coverPath)
        val shown = com.spotifry.music.data.shownForPlaylist(look)
        com.spotifry.music.ui.names.NameStyleSheet(
            name, playlist?.let(::countsLabel).orEmpty(), look, shown, logoFile, logoFile, null, tint ?: Color.White,
            backdrop = {
                CoverMosaic(coverSongs, Modifier.matchParentSize(), custom = coverPath)
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .7f)))))
            },
            onRename = { renaming = true },
            onStyle = { vm.setNameStyle(id, it) },
            onPickLogo = { logoPicker.launch(arrayOf("image/png", "image/webp", "image/svg+xml", "image/jpeg")) },
            onUseOwn = { vm.useNameLogo(id) },
            onUseOfficial = null,
            onRemoveLogo = { vm.removeNameLogo(id) },
            onTint = { vm.setLogoTint(id, it) },
            onClose = { nameSheet = false },
        )
    }
    if (coverSheet) ChangeCoverSheet(
        hasCustom = playlist?.cover != null, canPickSong = !songs.isNullOrEmpty(),
        onGallery = { coverSheet = false; gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onSongs = { coverSheet = false; picking = true },
        onDefault = { coverSheet = false; scope.launch { changeCover(null) } },
        onClose = { coverSheet = false },
    )
    if (renaming) NameDialog("Rename playlist", "Save", name, { renaming = false }) { vm.rename(id, it); renaming = false }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete this playlist?") },
        text = { Text("The songs stay on the phone. Only the playlist goes.") },
        confirmButton = { TextButton({ vm.delete(id); deleting = false; onBack() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
        dismissButton = { TextButton({ deleting = false }) { Text("Keep it") } },
    )
}

/** The page colour for the fade under the cover: the colour of the chosen picture, or the average of the default cover's songs. */
@Composable
private fun rememberCoverTint(songs: List<SongEntity>, coverPath: String?): Color? {
    val client = LocalImageClient.current
    val files = LocalContext.current.contentResolver
    val key = if (coverPath != null) listOf(coverPath) else defaultCoverSongs(songs).map { it.id }
    return produceState<Color?>(null, key, client) {
        value = withContext(Dispatchers.Default) {
            if (coverPath != null) coverBitmap(client, null, coverPath, files)?.let(::tintOf)
            else averageTint(defaultCoverSongs(songs).mapNotNull { coverBitmap(client, it.thumbnailUrl, it.thumbPath, files) })
        }
    }.value
}

/** The songs whose covers make the default cover: four in a square, or just the first one when there are fewer. */
internal fun defaultCoverSongs(songs: List<SongEntity>) = songs.take(4)

/** One colour for several covers: their average colour, put through the same recipe as a single cover. Null with no covers. */
internal fun averageTint(covers: List<Bitmap>): Color? {
    if (covers.isEmpty()) return null
    val px = covers.map { Bitmap.createScaledBitmap(it, 1, 1, true).getPixel(0, 0) }
    val one = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    one.setPixel(0, 0, android.graphics.Color.rgb(px.sumOf { android.graphics.Color.red(it) } / px.size, px.sumOf { android.graphics.Color.green(it) } / px.size, px.sumOf { android.graphics.Color.blue(it) } / px.size))
    return tintOf(one)
}

/** The cover over the top of the screen with the name on it, its colour fading down into the page. */
@Composable
private fun PlaylistHero(name: String, subtitle: String?, coverSongs: List<SongEntity>, coverPath: String?, tint: Color?, onBack: () -> Unit, onMenu: () -> Unit, menuHost: @Composable () -> Unit, look: com.spotifry.music.data.NameLook, logo: java.io.File?) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val heroH = LocalConfiguration.current.screenHeightDp.dp * .55f
    val statusH = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val toned = fadeColor(tint, scheme.background, scheme.surfaceContainerHigh)
    val ink = if (scheme.background.luminance() < .5f) Color.White else scheme.onBackground
    com.spotifry.music.ui.theme.WhiteStatusIcons()
    Box(Modifier.fillMaxWidth().height(heroH)) {
        CoverMosaic(coverSongs, Modifier.matchParentSize(), custom = coverPath)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(colorStops = arrayOf(0f to Color.Black.copy(alpha = .45f), .2f to Color.Transparent, .4f to Color.Transparent, .55f to toned.copy(alpha = .22f), .7f to toned.copy(alpha = .55f), .85f to toned.copy(alpha = .86f), 1f to toned))))
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = statusH + 4.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            val pill = Color.Black.copy(alpha = .45f)
            RoundButton(onBack, true, 48.dp, pill) { Icon(Icons.Back, "Back", Modifier.size(22.dp)) }
            Box {
                RoundButton(onMenu, true, 48.dp, pill) { Icon(Icons.More, "Playlist options", Modifier.size(22.dp)) }
                menuHost()
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 22.dp), Arrangement.spacedBy(4.dp), Alignment.CenterHorizontally) {
            com.spotifry.music.ui.names.StyledName(name, com.spotifry.music.data.shownForPlaylist(look), logo, ink, tint ?: ink, 50.sp, fallback = look.style)
            if (subtitle != null) com.spotifry.music.ui.names.NameMeta(subtitle, ink)
        }
    }
}

/** What the cover fades into: dark pages take the cover's colour a little darker, light pages a soft touch of it; a quiet grey until the cover has loaded. */
internal fun fadeColor(tint: Color?, page: Color, quiet: Color): Color = when {
    tint == null -> quiet
    page.luminance() < .5f -> lerp(tint, page, .3f)
    // plain mixing, not lerp: it keeps a light page light, so the dark title stays readable on it
    else -> Color(page.red + (tint.red - page.red) * .2f, page.green + (tint.green - page.green) * .2f, page.blue + (tint.blue - page.blue) * .2f)
}

@Composable
fun PlaylistDetailContent(
    name: String, songs: List<SongEntity>, query: String, onQuery: (String) -> Unit,
    order: PlaylistOrder, onOrder: (PlaylistOrder) -> Unit, onEditOrder: () -> Unit, onMixUp: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit, onMore: (Song) -> Unit, onMenu: () -> Unit, onBack: () -> Unit,
    modifier: Modifier = Modifier, bottomInset: Dp = 0.dp, subtitle: String? = null, loading: Boolean = false,
    onShuffle: (List<Song>) -> Unit = {}, coverSongs: List<SongEntity> = songs.take(4), coverPath: String? = null, onEdit: () -> Unit = {},
    look: com.spotifry.music.data.NameLook = com.spotifry.music.data.NameLook(), logo: java.io.File? = null,
    menuHost: @Composable () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val all = remember(songs) { songs.map { it.toSong() } }
    val searching = query.isNotBlank()
    val shown = remember(songs, query) { if (searching) songs.indices.filter { songs[it].matches(query) } else songs.indices.toList() }
    val tint = rememberCoverTint(coverSongs, coverPath)
    val toned = fadeColor(tint, scheme.background, scheme.surfaceContainerHigh)
    val ready = songs.isNotEmpty()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 16.dp)) {
        item { PlaylistHero(name, subtitle, coverSongs, coverPath, tint, onBack, onMenu, menuHost, look, logo) }
        item {
            val glass = scheme.onBackground.copy(alpha = .1f)
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(toned, scheme.background))).padding(top = 6.dp, bottom = 22.dp), Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally), Alignment.CenterVertically) {
                val ink = scheme.onBackground
                RoundButton({ onShuffle(all) }, ready, 52.dp, glass, ink) { Icon(Icons.Shuffle, "Shuffle", Modifier.size(23.dp)) }
                RoundButton({ onPlay(all, 0) }, ready, 88.dp, scheme.primary, scheme.onPrimary) { Icon(Icons.Play, "Play", Modifier.size(38.dp)) }
                RoundButton(onEdit, true, 52.dp, glass, ink) { Icon(Icons.Edit, "Rename or change cover", Modifier.size(23.dp)) }
            }
            if (songs.size > 1 || searching) Row(Modifier.padding(start = 20.dp, end = 20.dp), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
                SearchField(query, onQuery, {}, Modifier.weight(1f), hint = "Search in $name")
                if (!searching) OrderButton(order, onOrder, onEditOrder, onMixUp)
            }
            if (searching) Text(
                "${shown.size} of ${songs.size} songs", Modifier.padding(start = 20.dp, top = 10.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (loading) item {
            // the search box and order button, then rows, as they'll be with songs in the list
            LoadingShapes(Modifier.padding(start = 20.dp, end = 8.dp)) {
                Row(Modifier.padding(end = 12.dp), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
                    LoadingBox(Modifier.weight(1f).height(52.dp), CircleShape)
                    LoadingBox(Modifier.size(48.dp), CircleShape)
                }
                repeat(5) { LoadingRow() }
            }
        }
        else if (songs.isEmpty()) item { EmptyState("No songs yet", "Add songs from search or from any song\u2019s menu") }
        // a found song plays from its place in the whole playlist, so the rest follows after it
        items(shown.size, key = { songs[shown[it]].id }) { k ->
            val i = shown[k]
            val s = songs[i]
            SongRow(all[i], s.isSaved, { onPlay(all, i) }, { onMore(all[i]) }, Modifier.padding(start = 20.dp, end = 8.dp), localCover = s.thumbPath, highlight = query.takeIf { searching })
        }
    }
}

@Composable
private fun OrderButton(order: PlaylistOrder, onOrder: (PlaylistOrder) -> Unit, onEdit: () -> Unit, onMixUp: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box {
        // icon only, so the search box gets the room; the menu shows which order is picked
        IconButton({ open = true }, Modifier.size(48.dp), colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(containerColor = scheme.surface, contentColor = scheme.onSurface)) {
            Icon(Icons.Sort, "Order: ${order.label}", Modifier.size(20.dp))
        }
        DropdownMenu(open, { open = false }, shape = RoundedCornerShape(16.dp)) {
            PlaylistOrder.entries.forEach { o ->
                val on = o == order
                DropdownMenuItem(
                    text = { Text(o.label, color = if (on) scheme.primary else scheme.onSurface, fontWeight = if (on) FontWeight.SemiBold else null) },
                    leadingIcon = { if (on) Icon(Icons.Check, null, tint = scheme.primary) else Spacer(Modifier.size(24.dp)) },
                    onClick = { open = false; onOrder(o) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = scheme.outlineVariant)
            DropdownMenuItem(text = { Text("Change your order") }, leadingIcon = { Icon(Icons.Reorder, null) }, onClick = { open = false; onEdit() })
            DropdownMenuItem(text = { Text("Mix up your order") }, leadingIcon = { Icon(Icons.Shuffle, null) }, onClick = { open = false; onMixUp() })
        }
    }
}

/** Hold a song (or its handle) and drag it; the list scrolls by itself near the edges. Done or back saves. */
@Composable
private fun OrderEditor(songs: List<SongEntity>, bottomInset: Dp, onDone: (List<String>) -> Unit) {
    var list by remember(songs) { mutableStateOf(songs) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val a = list.indexOfFirst { it.id == from.key }
        val b = list.indexOfFirst { it.id == to.key }
        if (a >= 0 && b >= 0) list = list.toMutableList().apply { add(b, removeAt(a)) }
    }
    BackHandler { onDone(list.map { it.id }) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Change order", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            Button({ onDone(list.map { it.id }) }) { Text("Done") }
        }
        Text(
            "Hold a song and drag it up or down.", Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.weight(1f), listState, contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = bottomInset + 16.dp)) {
            itemsIndexed(list, key = { _, s -> s.id }) { _, s ->
                ReorderableItem(reorder, key = s.id) { dragging ->
                    val lift by animateDpAsState(if (dragging) 8.dp else 0.dp, label = "lift")
                    Surface(
                        Modifier.longPressDraggableHandle(), shape = RoundedCornerShape(14.dp), shadowElevation = lift,
                        color = if (dragging) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background,
                    ) {
                        Row(Modifier.fillMaxWidth().height(70.dp).padding(start = 10.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
                            Cover(s.id, s.thumbnailUrl, s.thumbPath, 52.dp, radius = 10.dp)
                            Column(Modifier.weight(1f)) {
                                Text(s.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton({}, Modifier.draggableHandle().size(48.dp)) { Icon(Icons.DragHandle, "Drag to move ${s.title}") }
                        }
                    }
                }
            }
        }
    }
}
