package com.spotifry.music.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.source.MusicSource
import com.spotifry.music.data.source.PageContent
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.clockText
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.Cover
import com.spotifry.music.ui.components.ErrorRow
import com.spotifry.music.ui.components.LoadingRow
import com.spotifry.music.ui.components.PlayButtons
import com.spotifry.music.ui.components.SectionTitle
import com.spotifry.music.ui.components.SongRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** What an album, artist or playlist can do; [save]'s songs are null when the page isn't loaded yet. */
class PageActions(
    val open: (PageLink) -> Unit = {},
    val save: (PageLink, List<Song>?) -> Unit = { _, _ -> },
    val share: (PageLink) -> Unit = {},
)

class PageViewModel(private val source: MusicSource, val link: PageLink) : ViewModel() {
    /** Null while loading. */
    val page = MutableStateFlow<Result<PageContent>?>(null)
    init { load() }
    fun load() {
        page.value = null
        viewModelScope.launch { page.value = runCatching { source.page(link) } }
    }
}

/** The line under the name: "Radiohead\nAlbum · 12 songs", "by Ann · 28 songs" or "Artist". */
internal fun pageLine(link: PageLink, page: PageContent?): String {
    val by = page?.by ?: link.by
    val count = page?.songs?.size?.let { " · $it ${if (it == 1) "song" else "songs"}" }.orEmpty()
    return when (link.type) {
        SearchType.ARTISTS -> "Artist"
        SearchType.PLAYLISTS -> (if (by.isBlank()) "Playlist" else "by $by") + count
        else -> listOfNotNull(by.ifBlank { null }, (page?.kind ?: "Album") + count).joinToString("\n")
    }
}

/** An album or playlist page (built like your own playlists), or an artist's. */
@Composable
fun PageScreen(
    vm: PageViewModel, savedIds: Set<String>, onBack: () -> Unit, onPlay: (List<Song>, Int, String) -> Unit, onShuffle: (List<Song>, String) -> Unit,
    onMore: (Song) -> Unit, actions: PageActions, bottomInset: Dp,
) {
    val state by vm.page.collectAsStateWithLifecycle()
    val link = vm.link
    val p = state?.getOrNull()
    val artist = link.type == SearchType.ARTISTS
    val album = link.type == SearchType.ALBUMS
    var menu by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                IconButton(onBack, Modifier.size(48.dp)) { Icon(Icons.Back, "Back") }
                Box {
                    IconButton({ menu = true }, Modifier.size(48.dp)) { Icon(Icons.More, "More options") }
                    PageMenu(menu, { menu = false }, link, actions, p?.songs?.takeIf { !artist && it.isNotEmpty() })
                }
            }
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp), Arrangement.spacedBy(16.dp), Alignment.Bottom) {
                Cover(link.url, p?.thumbnailUrl ?: link.thumbnailUrl, null, if (artist) 104.dp else 132.dp, shape = if (artist) CircleShape else RoundedCornerShape(14.dp))
                Column {
                    Text(p?.name ?: link.name, style = MaterialTheme.typography.headlineLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(pageLine(link, p), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!p?.songs.isNullOrEmpty()) PlayButtons({ onPlay(p!!.songs, 0, p.name) }, { onShuffle(p!!.songs, p.name) }, Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp))
        }
        when {
            state == null -> item { Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) { repeat(4) { LoadingRow() } } }
            p == null -> item { ErrorRow(link.name, vm::load, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            else -> {
                if (artist && p.songs.isNotEmpty()) item { SectionTitle("Songs") }
                itemsIndexed(p.songs) { i, s ->
                    SongRow(
                        s, s.id in savedIds, { onPlay(p.songs, i, p.name) }, { onMore(s) }, Modifier.padding(start = 20.dp, end = 8.dp),
                        subtitleExtra = if (album) clockText(s.durationSec * 1000) else null,
                        // one album shares one cover: the track number takes its place
                        leading = if (album) { { Text("${i + 1}", Modifier.width(28.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) } } else null,
                    )
                }
                if (p.albums.isNotEmpty()) item {
                    SectionTitle("Albums")
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(p.albums) { a ->
                            Column(Modifier.width(100.dp).clickable(onClickLabel = "Open album") { actions.open(a) }) {
                                Cover(a.url, a.thumbnailUrl, null, 100.dp, radius = 12.dp)
                                Text(a.name, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (p.songs.isEmpty() && p.albums.isEmpty()) item {
                    Text("Nothing to play here.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Save as playlist (albums and playlists only; [songs] null loads them first) and Share. */
@Composable
fun PageMenu(open: Boolean, onDismiss: () -> Unit, link: PageLink, actions: PageActions, songs: List<Song>?) {
    DropdownMenu(open, onDismiss, shape = RoundedCornerShape(16.dp)) {
        if (link.type != SearchType.ARTISTS) DropdownMenuItem(
            text = { Text("Save as playlist") }, leadingIcon = { Icon(Icons.Plus, null) },
            onClick = { onDismiss(); actions.save(link, songs) },
        )
        DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Share, null) }, onClick = { onDismiss(); actions.share(link) })
    }
}
