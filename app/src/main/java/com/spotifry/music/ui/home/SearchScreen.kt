package com.spotifry.music.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.clockText
import com.spotifry.music.ui.components.Cover
import com.spotifry.music.ui.components.CoverArt
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.ErrorRow
import com.spotifry.music.ui.components.LoadingRow
import com.spotifry.music.ui.components.LoadingShapes
import com.spotifry.music.ui.components.ScreenHeader
import com.spotifry.music.ui.components.SongRow

@Composable
fun SearchScreen(vm: HomeViewModel, onMore: (Song) -> Unit, bottomInset: Dp, actions: PageActions, modifier: Modifier = Modifier) {
    val search by vm.search.collectAsStateWithLifecycle()
    val text by vm.queryText.collectAsStateWithLifecycle()
    val saved by vm.savedIds.collectAsStateWithLifecycle()
    val history by vm.recentSearches.list.collectAsStateWithLifecycle()
    val local by vm.localResults.collectAsStateWithLifecycle()
    val type by vm.type.collectAsStateWithLifecycle()
    SearchContent(
        text, vm::onQueryChanged, search, saved, history, local = local,
        onPlay = vm::playFromSearch, onRetry = vm::retry, onSubmit = vm::submit, onMore = onMore,
        onRemove = vm.recentSearches::remove, onClearHistory = vm.recentSearches::clear,
        modifier = modifier, bottomInset = bottomInset, type = type, onType = { vm.type.value = it }, actions = actions,
    )
}

@Composable
fun SearchContent(
    text: String, onText: (String) -> Unit, search: SearchState, savedIds: Set<String>, history: List<String>,
    onPlay: (List<Song>, Int) -> Unit, onRetry: () -> Unit, onSubmit: () -> Unit, onMore: (Song) -> Unit,
    onRemove: (String) -> Unit = {}, onClearHistory: () -> Unit = {}, modifier: Modifier = Modifier, bottomInset: Dp = 0.dp,
    /** Music files on the phone that match, listed first. */
    local: List<Song> = emptyList(),
    type: SearchType = SearchType.SONGS, onType: (SearchType) -> Unit = {}, actions: PageActions = PageActions(),
) {
    val scheme = MaterialTheme.colorScheme
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 20.dp)) {
        item {
            ScreenHeader("Search")
            SearchField(text, onText, onSubmit, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp))
            if (search !is SearchState.Idle) TypeRow(type, onType)
        }
        when {
            search !is SearchState.Idle -> item {
                Column(Modifier.padding(start = 20.dp, end = 8.dp)) {
                    // phone files are songs: only under Songs
                    if (local.isNotEmpty() && type == SearchType.SONGS) {
                        GroupLabel("On this phone")
                        local.forEachIndexed { i, s -> SongRow(s, false, { onPlay(local, i) }, { onMore(s) }) }
                        GroupLabel("Songs", Modifier.padding(top = 12.dp))
                    }
                    SearchResults(search, savedIds, onPlay, onRetry, onMore, type = type, actions = actions)
                }
            }
            history.isEmpty() -> item {
                Text("Find any songs", Modifier.padding(horizontal = 20.dp, vertical = 20.dp), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
            }
            else -> {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("Recent searches", style = MaterialTheme.typography.headlineSmall)
                        TextButton(onClearHistory) { Text("Clear") }
                    }
                }
                items(history, key = { it }) { q ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClickLabel = "Search again") { onText(q) }.padding(start = 20.dp, end = 8.dp),
                        Arrangement.spacedBy(14.dp), Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Clock, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                        Text(q, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton({ onRemove(q) }, Modifier.size(48.dp)) { Icon(Icons.Close, "Remove $q", Modifier.size(20.dp), tint = scheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier.padding(top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun SearchResults(
    search: SearchState, savedIds: Set<String>, onPlay: (List<Song>, Int) -> Unit, onRetry: () -> Unit, onMore: (Song) -> Unit,
    modifier: Modifier = Modifier, type: SearchType = SearchType.SONGS, actions: PageActions = PageActions(),
) {
    Column(modifier) {
        when (search) {
            SearchState.Idle -> Unit
            SearchState.Loading -> LoadingShapes { repeat(3) { LoadingRow(width = if (type == SearchType.VIDEOS) 92.dp else 52.dp, round = type == SearchType.ARTISTS) } }
            is SearchState.Results -> {
                val t = search.type
                if (search.songs.isEmpty() && search.pages.isEmpty()) Text(
                    if (t == SearchType.SONGS) "No songs found. Try another spelling." else "No ${t.word} found for \"${search.query}\". Try another spelling, or pick Songs.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp),
                )
                search.songs.forEachIndexed { i, s ->
                    SongRow(s, s.id in savedIds, { onPlay(search.songs, i) }, { onMore(s) }, leading = if (t == SearchType.VIDEOS) { { VideoArt(s) } } else null)
                }
                search.pages.forEach { PageRow(it, actions) }
            }
            is SearchState.Error -> ErrorRow(if (search.type == SearchType.SONGS) search.query else "${search.type.word} for \"${search.query}\"", onRetry, Modifier.padding(end = 12.dp))
        }
    }
}

/** A music video's wide picture with its length in the corner. */
@Composable
private fun VideoArt(s: Song) = Box(Modifier.size(92.dp, 52.dp)) {
    CoverArt(s.id, s.thumbnailUrl, s.localThumbPath, Modifier.matchParentSize(), RoundedCornerShape(8.dp), large = false)
    Text(
        clockText(s.durationSec * 1000), Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = .66f)).padding(horizontal = 5.dp, vertical = 1.dp),
        color = Color.White, style = MaterialTheme.typography.labelSmall,
    )
}

/** An album or playlist (square cover, ⋮ with Save as playlist and Share) or an artist (round photo, no ⋮). */
@Composable
private fun PageRow(link: PageLink, actions: PageActions) {
    val artist = link.type == SearchType.ARTISTS
    var menu by remember { mutableStateOf(false) }
    val by = if (link.type == SearchType.PLAYLISTS && link.by.isNotBlank()) "by ${link.by}" else link.by
    Box {
        SongRow(
            Song(link.url, link.name, by, 0, link.thumbnailUrl), false, { actions.open(link) }, if (artist) null else { { menu = true } },
            leading = { Cover(link.url, link.thumbnailUrl, null, 52.dp, shape = if (artist) CircleShape else RoundedCornerShape(10.dp)) },
        )
        Box(Modifier.align(Alignment.BottomEnd)) { PageMenu(menu, { menu = false }, link, actions, null) }
    }
}

/** Songs, Music videos, Albums, Artists, Playlists: slides sideways, and keeps the picked one in view. */
@Composable
private fun TypeRow(type: SearchType, onType: (SearchType) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val state = rememberLazyListState()
    LaunchedEffect(type) { state.animateScrollToItem((type.ordinal - 1).coerceAtLeast(0)) }
    LazyRow(Modifier.padding(top = 4.dp, bottom = 4.dp), state, PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(SearchType.entries) { t ->
            val on = t == type
            Box(
                Modifier.height(40.dp).clip(CircleShape).background(if (on) scheme.primary else scheme.surfaceContainerHigh)
                    .selectable(on, role = Role.Tab) { onType(t) }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(t.label, style = MaterialTheme.typography.labelLarge, color = if (on) scheme.onPrimary else scheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun SearchField(text: String, onText: (String) -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier, hint: String = "Songs or artists") {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(scheme.onSurface.copy(alpha = .06f))
            .border(1.dp, scheme.outline, CircleShape).padding(start = 18.dp, end = 4.dp),
        Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
    ) {
        Icon(Icons.Search, null, Modifier.size(22.dp), tint = scheme.onSurfaceVariant)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text(hint, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                text, onText, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // the search key runs the search again even for the same words, which also reloads covers that failed
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (text.isNotEmpty()) IconButton({ onText("") }, Modifier.size(44.dp)) { Icon(Icons.Close, "Clear search", Modifier.size(20.dp), tint = scheme.onSurfaceVariant) }
        else Box(Modifier.size(14.dp))
    }
}
