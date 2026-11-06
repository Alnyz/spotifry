package com.spotifry.music.ui.saved

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.data.source.Song
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.EmptyState
import com.spotifry.music.ui.components.PlayButtons
import com.spotifry.music.ui.components.ScreenHeader
import com.spotifry.music.ui.components.SongRow
import com.spotifry.music.ui.settings.Chip

@Composable
fun SavedScreen(vm: SavedViewModel, onFindSong: () -> Unit, onMore: (Song) -> Unit, bottomInset: Dp, modifier: Modifier = Modifier) {
    val view by vm.view.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(vm.allowed()) }
    // Android asks its own question; a no leaves the card in place
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it; if (it) vm.refreshPhone() }
    // showing a list that has phone files reads them again, so new ones show up
    LaunchedEffect(view.filter) { if (view.filter != SavedFilter.Offline) { allowed = vm.allowed(); vm.refreshPhone() } }
    // back from Android's settings with access given
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { val now = vm.allowed(); if (now != allowed) { allowed = now; vm.refreshPhone() } }
    SavedContent(
        view, vm::pick, onFindSong, vm::play, onMore, modifier, bottomInset, onShuffle = vm::shuffle,
        phoneAllowed = allowed, onAllow = { ask.launch(Manifest.permission.READ_MEDIA_AUDIO) },
    )
}

@Composable
fun SavedContent(
    view: SavedView, onFilter: (SavedFilter) -> Unit, onFindSong: () -> Unit, onPlay: (List<Song>, Int) -> Unit, onMore: (Song) -> Unit,
    modifier: Modifier = Modifier, bottomInset: Dp = 0.dp,
    onShuffle: (List<Song>) -> Unit = {}, phoneAllowed: Boolean = true, onAllow: () -> Unit = {},
) {
    val rows = view.rows
    val songs = remember(rows) { rows.map { it.song } }
    val count = "${rows.size} ${if (rows.size == 1) "song" else "songs"}"
    val subtitle = when {
        rows.isEmpty() -> null
        view.filter == SavedFilter.Offline -> "$count · plays without internet"
        else -> count
    }
    val rowPad = Modifier.padding(start = 20.dp, end = 8.dp)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 16.dp)) {
        item { ScreenHeader("Saved", subtitle = subtitle) }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().padding(horizontal = 20.dp, vertical = 12.dp), Arrangement.spacedBy(8.dp)) {
                SavedFilter.entries.forEach { Chip(it.label, it == view.filter) { onFilter(it) } }
            }
        }
        when {
            view.filter == SavedFilter.Phone && !phoneAllowed -> item { AskCard(onAllow) }
            rows.isEmpty() -> item { Empty(view.filter, phoneAllowed, onFindSong) }
            else -> {
                item {
                    PlayButtons({ onPlay(songs, 0) }, { onShuffle(songs) }, Modifier.padding(start = 20.dp, top = 4.dp))
                    Spacer(Modifier.height(10.dp))
                }
                itemsIndexed(rows, key = { _, r -> r.song.id }) { i, r ->
                    SongRow(r.song, r.saved, { onPlay(songs, i) }, { onMore(r.song) }, rowPad, localCover = r.song.localThumbPath)
                }
            }
        }
    }
}

@Composable
private fun Empty(filter: SavedFilter, phoneAllowed: Boolean, onFindSong: () -> Unit) = when (filter) {
    SavedFilter.All -> EmptyState("Nothing here yet", if (phoneAllowed) "Save a song from search, or add music files to this phone, and it shows up here." else "Save a song from search, or allow music on this phone, and it shows up here.", "Find a song to save", onFindSong)
    SavedFilter.Offline -> EmptyState("Nothing saved yet", "Open any song and tap “Save on this phone”. It will play here without internet.", "Find a song to save", onFindSong)
    SavedFilter.Phone -> EmptyState("No music files found", "Songs you copy or download to this phone, like MP3s, show up here.")
    SavedFilter.Lately -> EmptyState("Nothing played lately", "Saved songs and music from this phone that you play show up here, newest first.")
}

/** First visit to "On this phone": what it does, and the button that makes Android ask. */
@Composable
private fun AskCard(onAllow: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(scheme.surface).padding(horizontal = 20.dp, vertical = 22.dp),
        Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(Icons.Note, null, Modifier.size(28.dp), tint = scheme.primary)
        }
        Text("Play the music on your phone", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            "Songs you already have, like MP3s you copied over or downloaded elsewhere, show up here and in search, next to YouTube songs.",
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Button(onAllow, Modifier.padding(top = 10.dp).fillMaxWidth().defaultMinSize(minHeight = 48.dp), shape = CircleShape) { Text("Allow access to music") }
    }
}
