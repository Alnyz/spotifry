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
import com.spotifry.music.data.source.Song
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.ErrorRow
import com.spotifry.music.ui.components.LoadingRow
import com.spotifry.music.ui.components.ScreenHeader
import com.spotifry.music.ui.components.SongRow

@Composable
fun SearchScreen(vm: HomeViewModel, onMore: (Song) -> Unit, bottomInset: Dp, modifier: Modifier = Modifier) {
    val search by vm.search.collectAsStateWithLifecycle()
    val text by vm.queryText.collectAsStateWithLifecycle()
    val saved by vm.savedIds.collectAsStateWithLifecycle()
    val history by vm.recentSearches.list.collectAsStateWithLifecycle()
    SearchContent(
        text, vm::onQueryChanged, search, saved, history,
        onPlay = vm::playFromSearch, onRetry = vm::retry, onSubmit = vm::submit, onMore = onMore,
        onRemove = vm.recentSearches::remove, onClearHistory = vm.recentSearches::clear,
        modifier = modifier, bottomInset = bottomInset,
    )
}

@Composable
fun SearchContent(
    text: String, onText: (String) -> Unit, search: SearchState, savedIds: Set<String>, history: List<String>,
    onPlay: (List<Song>, Int) -> Unit, onRetry: () -> Unit, onSubmit: () -> Unit, onMore: (Song) -> Unit,
    onRemove: (String) -> Unit = {}, onClearHistory: () -> Unit = {}, modifier: Modifier = Modifier, bottomInset: Dp = 0.dp,
) {
    val scheme = MaterialTheme.colorScheme
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 20.dp)) {
        item {
            ScreenHeader("Search")
            SearchField(text, onText, onSubmit, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp))
        }
        when {
            search !is SearchState.Idle -> item { SearchResults(search, savedIds, onPlay, onRetry, onMore, Modifier.padding(start = 20.dp, end = 8.dp)) }
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
fun SearchResults(search: SearchState, savedIds: Set<String>, onPlay: (List<Song>, Int) -> Unit, onRetry: () -> Unit, onMore: (Song) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        when (search) {
            SearchState.Idle -> Unit
            SearchState.Loading -> repeat(3) { LoadingRow() }
            is SearchState.Results -> {
                if (search.songs.isEmpty()) Text("No songs found. Try another spelling.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                search.songs.forEachIndexed { i, s -> SongRow(s, s.id in savedIds, { onPlay(search.songs, i) }, { onMore(s) }) }
            }
            is SearchState.Error -> ErrorRow(search.query, onRetry, Modifier.padding(end = 12.dp))
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
