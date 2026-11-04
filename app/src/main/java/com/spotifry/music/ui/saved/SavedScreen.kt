package com.spotifry.music.ui.saved

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.source.Song
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.EmptyState
import com.spotifry.music.ui.components.PlayButtons
import com.spotifry.music.ui.components.ScreenHeader
import com.spotifry.music.ui.components.SongRow
import com.spotifry.music.ui.settings.Segments

/** The "On this phone" tab: whether music access is allowed, the files found, and what tapping does. */
class PhoneTab(val allowed: Boolean, val songs: List<Song>, val onAllow: () -> Unit, val onPlay: (List<Song>, Int) -> Unit)

@Composable
fun SavedScreen(vm: SavedViewModel, onFindSong: () -> Unit, onMore: (Song) -> Unit, bottomInset: Dp, modifier: Modifier = Modifier) {
    val saved by vm.saved.collectAsStateWithLifecycle()
    val phone by vm.phone.collectAsStateWithLifecycle()
    var onPhone by rememberSaveable { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(vm.allowed()) }
    // Android asks its own question; a no leaves the card in place
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it; if (it) vm.refreshPhone() }
    // opening the tab reads the files again, so new ones (or access given in Android's settings) show up
    LaunchedEffect(onPhone) { if (onPhone) { allowed = vm.allowed(); vm.refreshPhone() } }
    SavedContent(
        saved, onFindSong, vm::play, onMore, modifier, bottomInset,
        onPhone = onPhone, onTab = { onPhone = it },
        phone = PhoneTab(allowed, phone, { ask.launch(Manifest.permission.READ_MEDIA_AUDIO) }, vm::playPhone),
    )
}

@Composable
fun SavedContent(
    saved: List<SongEntity>, onFindSong: () -> Unit, onPlay: (List<Song>, Int) -> Unit, onMore: (Song) -> Unit,
    modifier: Modifier = Modifier, bottomInset: Dp = 0.dp,
    onPhone: Boolean = false, onTab: (Boolean) -> Unit = {}, phone: PhoneTab? = null,
) {
    val subtitle = when {
        onPhone && phone != null -> if (phone.allowed && phone.songs.isNotEmpty()) "${phone.songs.size} ${if (phone.songs.size == 1) "song" else "songs"} on this phone" else "Plays without internet"
        saved.isEmpty() -> null
        else -> "${saved.size} ${if (saved.size == 1) "song" else "songs"} · plays without internet"
    }
    val rowPad = Modifier.padding(start = 20.dp, end = 8.dp)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 16.dp)) {
        item { ScreenHeader("Saved", subtitle = subtitle) }
        if (phone != null) item {
            Segments(listOf("Saved here", "On this phone"), if (onPhone) 1 else 0, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), fill = true) { onTab(it == 1) }
        }
        if (onPhone && phone != null) {
            when {
                !phone.allowed -> item { AskCard(phone.onAllow) }
                phone.songs.isEmpty() -> item { EmptyState("No music files found", "Songs you copy or download to this phone, like MP3s, show up here.") }
                else -> {
                    item {
                        PlayButtons({ phone.onPlay(phone.songs, 0) }, { phone.onPlay(phone.songs.shuffled(), 0) }, Modifier.padding(start = 20.dp, top = 16.dp))
                        Spacer(Modifier.height(10.dp))
                    }
                    itemsIndexed(phone.songs, key = { _, s -> s.id }) { i, s -> SongRow(s, false, { phone.onPlay(phone.songs, i) }, { onMore(s) }, rowPad) }
                }
            }
        } else if (saved.isEmpty()) {
            item {
                EmptyState(
                    "Nothing saved yet",
                    "Open any song and tap “Save on this phone”. It will play here without internet.",
                    "Find a song to save", onFindSong,
                )
            }
        } else {
            val songs = saved.map { it.toSong() }
            item {
                PlayButtons({ onPlay(songs, 0) }, { onPlay(songs.shuffled(), 0) }, Modifier.padding(start = 20.dp, top = 16.dp))
                Spacer(Modifier.height(10.dp))
            }
            itemsIndexed(saved, key = { _, s -> s.id }) { i, s ->
                SongRow(songs[i], true, { onPlay(songs, i) }, { onMore(songs[i]) }, rowPad, localCover = s.thumbPath)
            }
        }
    }
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
