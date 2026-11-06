package com.spotifry.music.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.source.Song
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.CoverArt
import com.spotifry.music.ui.components.ErrorRow
import com.spotifry.music.ui.components.LoadingBox
import com.spotifry.music.ui.components.LoadingShapes
import com.spotifry.music.ui.components.SectionTitle
import com.spotifry.music.ui.components.SongRow
import java.time.LocalTime

@Composable
fun HomeScreen(
    vm: HomeViewModel, onMore: (Song) -> Unit, bottomInset: Dp,
    onOpenSaved: () -> Unit, onOpenPlaylists: () -> Unit, modifier: Modifier = Modifier, onOpenSettings: () -> Unit = {},
    listening: @Composable () -> Unit = {}, top: @Composable () -> Unit = {},
) {
    val recent by vm.recentOrNull.collectAsStateWithLifecycle()
    val saved by vm.savedIds.collectAsStateWithLifecycle()
    val most by vm.mostPlayed.collectAsStateWithLifecycle()
    HomeContent(
        recent = recent.orEmpty(), loading = recent == null, savedIds = saved, mostPlayed = most,
        onPlay = vm::play, onMore = onMore, modifier = modifier, bottomInset = bottomInset,
        onOpenSaved = onOpenSaved, onOpenPlaylists = onOpenPlaylists, onShuffleSaved = vm::shuffleSaved, onOpenSettings = onOpenSettings, listening = listening, top = top,
    )
}

@Composable
fun HomeContent(
    recent: List<SongEntity>, savedIds: Set<String>,
    onPlay: (List<Song>, Int, String) -> Unit, onMore: (Song) -> Unit,
    modifier: Modifier = Modifier, bottomInset: Dp = 0.dp,
    onOpenSaved: () -> Unit = {}, onOpenPlaylists: () -> Unit = {}, onShuffleSaved: () -> Unit = {},
    mostPlayed: List<SongEntity> = emptyList(), onOpenSettings: () -> Unit = {},
    /** The history isn't read yet, so an empty [recent] says nothing about this person. */
    loading: Boolean = false,
    /** Shown under the greeting: the update card when there is one. */
    top: @Composable () -> Unit = {},
    /** Shown under the shortcuts: the way into Your listening. */
    listening: @Composable () -> Unit = {},
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Greeting(Modifier.weight(1f))
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable(onClickLabel = "Settings", onClick = onOpenSettings),
                    Alignment.Center,
                ) { Icon(Icons.Settings, "Settings", Modifier.size(22.dp)) }
            }
        }
        item { top() }
        val songs = recent.map { it.toSong() }
        if (recent.isNotEmpty()) item { LastPlayed(recent[0]) { onPlay(songs, 0, "Recently played") } }
        else if (loading) item {
            LoadingShapes { LoadingBox(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp).fillMaxWidth().height(212.dp), RoundedCornerShape(22.dp)) }
        }
        // with nothing played they'd jump up when the shape goes, so they wait for the read
        if (!loading) item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), Arrangement.spacedBy(10.dp)) {
                Shortcut(Icons.Download, "Saved", onOpenSaved)
                Shortcut(Icons.List, "Playlists", onOpenPlaylists)
                if (savedIds.isNotEmpty()) Shortcut(Icons.Shuffle, "Shuffle", onShuffleSaved)
            }
        }
        if (!loading) item { listening() }
        if (recent.isEmpty()) {
            if (!loading) item {
                Text(
                    "Find a song in Search to start. What you play shows up here.",
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@LazyColumn
        }
        if (recent.size > 1) {
            item { SectionTitle("Recently played") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(recent.drop(1), key = { _, s -> s.id }) { i, s ->
                        RecentCard(s, { onPlay(songs, i + 1, "Recently played") }, { onMore(songs[i + 1]) })
                    }
                }
            }
        }
        // five or nothing: a top list of two isn't a top list yet
        if (mostPlayed.size >= 5) {
            item { SectionTitle("Most played") }
            val top = mostPlayed.map { it.toSong() }
            itemsIndexed(top, key = { _, s -> "most-" + s.id }) { i, s ->
                Row(Modifier.padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(22.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SongRow(s, s.id in savedIds, { onPlay(top, i, "Most played") }, { onMore(s) }, Modifier.padding(start = 6.dp, end = 8.dp), localCover = mostPlayed[i].thumbPath)
                }
            }
        }
        item { SectionTitle("Keep listening") }
        // one song queued alone: when it ends, the player adds similar songs, so this plays as a mix
        item { MixCard(recent[0]) { onPlay(listOf(songs[0]), 0, "Mix from ${songs[0].title}") } }
    }
}

@Composable
private fun Greeting(modifier: Modifier = Modifier) {
    val hour = LocalTime.now().hour
    val part = when (hour) { in 5..11 -> "morning"; in 12..17 -> "afternoon"; in 18..21 -> "evening"; else -> "night" }
    val accent = MaterialTheme.colorScheme.primary
    Text(buildAnnotatedString { append("Good $part"); withStyle(SpanStyle(color = accent)) { append(".") } }, modifier, style = MaterialTheme.typography.displaySmall)
}

/** The last song, large, with its own cover as the picture. While it is the song playing, a tap opens Now playing. */
@Composable
private fun LastPlayed(s: SongEntity, onPlay: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val now = com.spotifry.music.ui.components.LocalNowPlaying.current
    val current = now != null && now.id == s.id
    Box(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp).fillMaxWidth().height(212.dp).clip(shape)
            .clickable(onClickLabel = if (current) "Open Now playing" else "Play again", onClick = if (current) now!!.open else onPlay),
    ) {
        CoverArt(s.id, s.thumbnailUrl, s.thumbPath, Modifier.fillMaxSize())
        // darkens the left side so the white text stays readable on any cover
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xEB08090C), Color(0x8C08090C), Color(0x2608090C)))))
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                "Last played", Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .16f)).padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium, color = Color.White,
            )
            Text(s.title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.headlineLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(s.artist, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyLarge, color = Color(0xFFD3D7DD), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White), Alignment.Center) {
                    if (current) com.spotifry.music.ui.components.PlayingBars(now!!.playing) else Icon(Icons.Play, null, Modifier.size(26.dp), tint = Color(0xFF0C0D10))
                }
                Text(if (current) "Now playing" else "Play again", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
    }
}

@Composable
private fun RowScope.Shortcut(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.weight(1f).height(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick),
        Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentCard(s: SongEntity, onPlay: () -> Unit, onMore: () -> Unit) {
    Column(Modifier.width(140.dp).combinedClickable(onClickLabel = "Play", onLongClickLabel = "More options", onLongClick = onMore, onClick = onPlay)) {
        Box {
            CoverArt(s.id, s.thumbnailUrl, s.thumbPath, Modifier.size(140.dp), RoundedCornerShape(14.dp))
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(34.dp).clip(CircleShape).background(Color(0xB80C0D10)), Alignment.Center) {
                Icon(Icons.Play, null, Modifier.size(18.dp), tint = Color.White)
            }
        }
        Text(s.title, Modifier.padding(top = 9.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(s.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MixCard(s: SongEntity, onPlay: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(86.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(scheme.primary.copy(alpha = .22f), scheme.surface), endX = 900f))
            .background(scheme.surface.copy(alpha = .35f))
            .clickable(onClickLabel = "Play mix", onClick = onPlay)
            .padding(horizontal = 16.dp),
        Arrangement.spacedBy(14.dp), Alignment.CenterVertically,
    ) {
        CoverArt(s.id, s.thumbnailUrl, s.thumbPath, Modifier.size(54.dp), RoundedCornerShape(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Mix from ${s.title}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Plays it, then similar songs", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(scheme.primary), Alignment.Center) {
            Icon(Icons.Mix, null, Modifier.size(22.dp), tint = scheme.onPrimary)
        }
    }
}

