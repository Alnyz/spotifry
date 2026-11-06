package com.spotifry.music.ui.share

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.Lyrics
import com.spotifry.music.data.LyricsSource
import com.spotifry.music.data.ShareText
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.source.isLocal
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.Cover
import com.spotifry.music.ui.components.LocalImageClient
import com.spotifry.music.ui.components.Pill
import com.spotifry.music.ui.components.SongRow
import com.spotifry.music.ui.components.coverBitmap
import com.spotifry.music.ui.components.tintOf
import com.spotifry.music.ui.settings.Segments
import com.spotifry.music.ui.theme.LocalAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a picture is made of: one song, or a playlist (its name, "N songs" and its songs). */
data class ShareTarget(val title: String, val subtitle: String, val songs: List<Song>, val playlist: Boolean = false) {
    companion object {
        fun of(song: Song) = ShareTarget(song.title, song.artist, listOf(song))
        fun of(name: String, songs: List<Song>) = ShareTarget(name, "${songs.size} ${if (songs.size == 1) "song" else "songs"}", songs, playlist = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) =
    ModalBottomSheet(onDismiss, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) { content() }
    }

/** A song's ⋮ menu → Share: a link (YouTube songs only) or a picture. */
@Composable
fun ShareSongSheet(song: Song, onPicture: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ShareSheet(onDismiss) {
        Text("Share this song", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 6.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            Cover(song.id, song.thumbnailUrl, song.localThumbPath, 44.dp, radius = 10.dp)
            Column {
                Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (song.isLocal) Option(Icons.Link, "Send a link", "This song is a file on your phone, so there's no link", enabled = false) {}
        else {
            Option(Icons.Link, "Send a link", "The song's name and its YouTube link. Anyone can open it, with or without Spotifry.") { sendText(context, ShareText.song(song)); onDismiss() }
            Message(ShareText.song(song))
        }
        Option(Icons.Picture, "Make a picture", "Cover, name and one line of the lyrics, for a story or a chat.", Modifier.padding(top = 6.dp)) { onPicture(); onDismiss() }
    }
}

/** A playlist's ⋮ menu → Share: the song list as text, or a picture. */
@Composable
fun SharePlaylistSheet(target: ShareTarget, onPicture: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember(target) { ShareText.playlist(target.title, target.songs) }
    ShareSheet(onDismiss) {
        Text("Share \"${target.title}\"", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(target.subtitle, Modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Option(Icons.List, "Send the song list", "Each song with its YouTube link. A friend with Spotifry can turn it back into this playlist.") { sendText(context, text); onDismiss() }
        Message(text, maxLines = 8)
        Option(Icons.Picture, "Send a picture", "The covers and the name, for a story. Shows the playlist, doesn't carry the songs.", Modifier.padding(top = 6.dp)) { onPicture(); onDismiss() }
    }
}

@Composable
private fun Option(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clickable(enabled, onClick = onClick).alpha(if (enabled) 1f else .5f).padding(horizontal = 24.dp, vertical = 14.dp),
        Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(scheme.surfaceContainerHigh), Alignment.Center) { Icon(icon, null, tint = scheme.primary) }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}

/** The text that will be sent, in a grey box. */
@Composable
private fun Message(text: String, maxLines: Int = Int.MAX_VALUE) = Text(
    text, Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 12.dp),
    style = MaterialTheme.typography.bodySmall, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
)

/** A full page over the app, with a back arrow and a title. */
@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
                IconButton(onBack, Modifier.size(48.dp)) { Icon(Icons.Back, "Back") }
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier.padding(top = 14.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)

/**
 * Make a picture: a live preview of the card, its shape, its background and (songs only) the lyric line.
 * [isPlaying] and [position] pick the line being sung now as the first choice.
 */
@Composable
fun MakePicturePage(target: ShareTarget, lyricsSource: LyricsSource, isPlaying: Boolean, position: () -> Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val client = LocalImageClient.current
    val accent = LocalAccent.current.dark
    val scope = rememberCoroutineScope()
    var story by remember { mutableStateOf(!target.playlist) }
    var bg by remember { mutableStateOf(CardBackground.COVER) }
    var line by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    // covers and the cover's colour, loaded once
    val art by produceState<Pair<List<Bitmap?>, androidx.compose.ui.graphics.Color?>?>(null, target) {
        value = withContext(Dispatchers.Default) {
            val covers = target.songs.take(4).map { coverBitmap(client, it.thumbnailUrl, it.localThumbPath, context.contentResolver) }
            covers to covers.firstOrNull { it != null }?.let(::tintOf)
        }
    }
    val lines by produceState<List<String>?>(null, target) {
        if (target.playlist) { value = emptyList(); return@produceState }
        val l = runCatching { lyricsSource.get(target.songs.first()) }.getOrNull()
        val all = when (l) { is Lyrics.Timed -> l.lines.map { it.text }; is Lyrics.Plain -> l.lines; null -> emptyList() }
        val clean = all.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        line = (if (isPlaying && l is Lyrics.Timed) l.lines.lastOrNull { it.atMs <= position() && it.text.isNotBlank() }?.text?.trim() else null) ?: clean.firstOrNull()
        value = clean
    }
    val card by produceState<Bitmap?>(null, art, story, bg, line, accent) {
        val (covers, tint) = art ?: return@produceState
        value = withContext(Dispatchers.Default) { drawCard(context, target.title, target.subtitle, line, covers, target.playlist, story, bg, tint, accent) }
    }

    if (picking) {
        PickLinePage(lines.orEmpty(), line, { line = it; picking = false }) { picking = false }
        return
    }
    Page("Make a picture", onBack) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 2.dp, bottom = 14.dp), Alignment.Center) {
                val ratio = if (story) 9f / 16 else 1f
                val img = card
                if (img != null) Image(img.asImageBitmap(), "Picture preview", Modifier.aspectRatio(ratio).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                else Box(Modifier.aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
            }
            if (!target.playlist) Segments(listOf("Story", "Square"), if (story) 0 else 1, Modifier.fillMaxWidth(), fill = true) { story = it == 0 }
            Label("Background")
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
                CardBackground.entries.forEach { b ->
                    val ink = cardInk(b, art?.second, accent)
                    val on = b == bg
                    Column(Modifier.weight(1f).clickable { bg = b }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().height(40.dp)
                                .border(2.dp, if (on) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
                                .padding(if (on) 4.dp else 0.dp).clip(RoundedCornerShape(if (on) 8.dp else 12.dp))
                                .background(Brush.verticalGradient(listOf(ink.top, ink.bottom))),
                        )
                        Text(
                            b.label, Modifier.padding(top = 5.dp), style = MaterialTheme.typography.labelMedium,
                            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // no lyrics found: no row, the picture is cover and name only
            if (!lines.isNullOrEmpty()) {
                Label("Lyric line")
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).clickable { picking = true }.padding(horizontal = 14.dp, vertical = 12.dp),
                    Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
                ) {
                    Icon(Icons.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(line ?: "No line, just the cover", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Change", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
            Pill(
                Icons.Share, "Share picture", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary,
                { card?.let { scope.launch { sharePicture(context, it) } } }, Modifier.fillMaxWidth().padding(top = 14.dp),
            )
        }
    }
}

/** The song's lyrics, one line per row; tapping one puts it on the picture. */
@Composable
private fun PickLinePage(lines: List<String>, picked: String?, onPick: (String?) -> Unit, onBack: () -> Unit) = Page("Pick a line", onBack) {
    val scheme = MaterialTheme.colorScheme
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
        item {
            Text(
                "One line goes on the picture. Long lines get smaller to fit.", Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }
        items(listOf<String?>(null) + lines) { l ->
            val on = l == picked
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (on) scheme.primary.copy(alpha = .14f) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onPick(l) }.padding(horizontal = 16.dp, vertical = 13.dp),
                Arrangement.spacedBy(10.dp), Alignment.CenterVertically,
            ) {
                if (l == null) Icon(Icons.None, null, tint = if (on) scheme.primary else scheme.onSurfaceVariant)
                Text(
                    l ?: "No line, just the cover", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    color = if (on) scheme.onSurface else scheme.onSurfaceVariant,
                )
                if (on) Icon(Icons.Check, "Picked", tint = scheme.primary)
            }
        }
    }
}

/** A song list someone sent: rename it, play it, or keep it as a playlist. */
@Composable
fun ImportSharedPage(shared: ShareText.Shared, onPlay: (List<Song>, Int, String) -> Unit, onAdd: (String) -> Unit, onBack: () -> Unit) {
    var name by remember(shared) { mutableStateOf(shared.title) }
    val scheme = MaterialTheme.colorScheme
    Page("Import playlist", onBack) {
        BoxWithConstraints {
        // with a keyboard up in landscape there's only room for the name being typed: the buttons come back with the keyboard's Done
        val roomy = maxHeight >= 160.dp
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = if (roomy) 16.dp else 0.dp)) {
            // the name scrolls with the songs and the buttons stay
            LazyColumn(Modifier.weight(1f)) {
                item {
                    if (roomy) Label("Name", Modifier.padding(top = 0.dp))
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp))
                    val n = shared.songs.size
                    val files = shared.phoneFiles
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = scheme.onSurface, fontWeight = FontWeight.Bold)) { append("$n ${if (n == 1) "song" else "songs"} ready.") }
                            if (files > 0) append(if (files == 1) " 1 left out: it was a file on their phone." else " $files left out: they were files on their phone.")
                        },
                        Modifier.padding(top = 14.dp, bottom = 6.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    )
                }
                itemsIndexed(shared.songs, key = { _, s -> s.id }) { i, s -> SongRow(s, false, { onPlay(shared.songs, i, name.ifBlank { shared.title }) }, null) }
            }
            if (roomy) Row(Modifier.fillMaxWidth().padding(top = 10.dp), Arrangement.spacedBy(10.dp)) {
                Pill(null, "Play now", scheme.surfaceContainerHigh, scheme.onSurface, { onPlay(shared.songs, 0, name.ifBlank { shared.title }) }, Modifier.weight(1f))
                Pill(null, "Add to my playlists", scheme.primary, scheme.onPrimary, { onAdd(name.ifBlank { shared.title }) }, Modifier.weight(1.6f))
            }
        }
        }
    }
}
