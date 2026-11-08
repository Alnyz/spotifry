package com.spotifry.music.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.data.db.PlaylistWithCounts
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.PlayerUiState
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.CoverArt
import com.spotifry.music.ui.components.CoverMosaic
import com.spotifry.music.ui.components.ErrorRow
import com.spotifry.music.ui.components.CardRowShape
import com.spotifry.music.ui.components.FADE_MS
import com.spotifry.music.ui.components.fadeIn
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.spotifry.music.ui.components.LoadingBox
import com.spotifry.music.ui.components.LoadingRow
import com.spotifry.music.ui.components.LoadingShapes
import com.spotifry.music.ui.components.PlaylistTile
import com.spotifry.music.ui.components.SectionTitle
import com.spotifry.music.ui.components.SongRow
import java.time.LocalTime

@Composable
fun HomeScreen(
    vm: HomeViewModel, onMore: (Song) -> Unit, bottomInset: Dp,
    onOpenSaved: () -> Unit, onOpenPlaylists: () -> Unit, modifier: Modifier = Modifier, onOpenSettings: () -> Unit = {},
    listening: @Composable () -> Unit = {}, top: @Composable () -> Unit = {},
    artists: ArtistsHome = ArtistsHome(), onOpenArtist: (com.spotifry.music.data.PickedArtist) -> Unit = {}, onPickArtists: () -> Unit = {},
    player: PlayerUiState = PlayerUiState(), onToggle: () -> Unit = {}, onPause: () -> Unit = {}, onOpenPlayer: () -> Unit = {},
    playlists: List<PlaylistWithCounts> = emptyList(), libraryRead: Boolean = true, covers: Map<Long, List<SongEntity>> = emptyMap(),
    onOpenPlaylist: (Long) -> Unit = {}, onPlayPlaylist: (PlaylistWithCounts) -> Unit = {},
    onSearch: () -> Unit = {}, onImport: () -> Unit = {}, onMood: (Mood) -> Unit = {},
) {
    val recent by vm.recentOrNull.collectAsStateWithLifecycle()
    val saved by vm.savedIds.collectAsStateWithLifecycle()
    val savedRead by vm.savedRead.collectAsStateWithLifecycle()
    val most by vm.mostPlayed.collectAsStateWithLifecycle()
    HomeContent(
        recent = recent.orEmpty(), loading = recent == null || !savedRead || !libraryRead, savedIds = saved, mostPlayed = most.orEmpty(), mostRead = most != null,
        onPlay = vm::play, onMore = onMore, modifier = modifier, bottomInset = bottomInset,
        onOpenSaved = onOpenSaved, onOpenPlaylists = onOpenPlaylists, onShuffleSaved = vm::shuffleSaved, onOpenSettings = onOpenSettings, listening = listening, top = top,
        artists = artists, onOpenArtist = onOpenArtist, onPickArtists = onPickArtists,
        player = player, onToggle = onToggle, onPause = onPause, onOpenPlayer = onOpenPlayer, playlists = playlists, covers = covers,
        onOpenPlaylist = onOpenPlaylist, onPlayPlaylist = onPlayPlaylist, onSearch = onSearch, onImport = onImport, onMood = onMood,
    )
}

@Composable
fun HomeContent(
    recent: List<SongEntity>, savedIds: Set<String>,
    onPlay: (List<Song>, Int, String) -> Unit, onMore: (Song) -> Unit,
    modifier: Modifier = Modifier, bottomInset: Dp = 0.dp,
    onOpenSaved: () -> Unit = {}, onOpenPlaylists: () -> Unit = {}, onShuffleSaved: () -> Unit = {},
    mostPlayed: List<SongEntity> = emptyList(), mostRead: Boolean = true, onOpenSettings: () -> Unit = {},
    /** The history, saved songs or playlists aren't read yet, so nothing can be said about this person. */
    loading: Boolean = false,
    /** Shown under the greeting: the update card when there is one. */
    top: @Composable () -> Unit = {},
    /** Shown under the shortcuts: the way into Your listening. */
    listening: @Composable () -> Unit = {},
    /** The artists picked on first launch, and their songs. */
    artists: ArtistsHome = ArtistsHome(), onOpenArtist: (com.spotifry.music.data.PickedArtist) -> Unit = {}, onPickArtists: () -> Unit = {},
    player: PlayerUiState = PlayerUiState(), onToggle: () -> Unit = {}, onPause: () -> Unit = {}, onOpenPlayer: () -> Unit = {},
    playlists: List<PlaylistWithCounts> = emptyList(), covers: Map<Long, List<SongEntity>> = emptyMap(),
    onOpenPlaylist: (Long) -> Unit = {}, onPlayPlaylist: (PlaylistWithCounts) -> Unit = {},
    onSearch: () -> Unit = {}, onImport: () -> Unit = {}, onMood: (Mood) -> Unit = {},
) {
    val songs = recent.map { it.toSong() }
    // a song that has run to its end is back to being the last one played
    val inPlayer = player.current?.takeUnless { player.ended }
    val heroSong = inPlayer ?: songs.firstOrNull()
    // nothing played yet: the newest playlist with songs takes the hero, and leaves the row below
    val heroList = if (heroSong == null) playlists.filter { it.songCount > 0 }.maxByOrNull { it.id } else null
    val otherSongs = recent.withIndex().filter { it.value.id != heroSong?.id }
    val otherLists = playlists.filter { it.id != heroList?.id }.sortedByDescending { it.id }
    // shapes were up and the real shelves are replacing them: the rows fade in once, then nothing animates
    val shapes = loading || artists.loading || !mostRead
    var hadShapes by remember { mutableStateOf(false) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(shapes) { if (shapes) hadShapes = true else { delay(FADE_MS + 100L); settled = true } }
    val fade = hadShapes && !settled
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
        when {
            heroSong != null -> item {
                val status = when {
                    inPlayer == null -> HeroStatus.LAST_PLAYED
                    player.isPlaying -> HeroStatus.ACTIVE
                    player.failed -> HeroStatus.FAILED
                    player.wantsPlay && player.buffering -> HeroStatus.ACTIVE
                    else -> HeroStatus.PAUSED
                }
                val again = { onPlay(songs, 0, "Recently played") }
                SongHero(heroSong, status, if (inPlayer == null) again else onOpenPlayer, when (status) {
                    HeroStatus.LAST_PLAYED -> again
                    HeroStatus.ACTIVE -> onPause
                    else -> onToggle
                })
            }
            loading -> item { HeroShape() }
            heroList != null -> item {
                Hero(
                    heroList.name, "${heroList.songCount} ${if (heroList.songCount == 1) "song" else "songs"}",
                    { CoverMosaic(covers[heroList.id].orEmpty(), Modifier.fillMaxSize()) },
                    "Your playlist", Icons.Play, "Play", "Open playlist", { onOpenPlaylist(heroList.id) }, { onPlayPlaylist(heroList) },
                )
            }
            artists.songs.isNotEmpty() -> item {
                val list = artists.songs
                val play = { onPlay(list, 0, "From your artists") }
                val who = list.map { it.artist }.distinct().size
                Hero(
                    "Songs from your artists", "${list.size} ${if (list.size == 1) "song" else "songs"}, $who ${if (who == 1) "artist" else "artists"}",
                    { CoverMosaic(remember(list) { list.take(4).map { it.toEntity() } }, Modifier.fillMaxSize()) },
                    "Start here", Icons.Play, "Play", "Play", play, play,
                )
            }
            artists.loading -> item { HeroShape() }
            artists.artists.isEmpty() -> item { StartCard(onSearch, onImport, onPickArtists) }
        }
        // reads not finished: each shelf is a shape of its real size in its real place, and goes if its read comes back empty
        if (loading) {
            item { CardRowShape(140.dp, 9.dp, title = "Recently played") }
            item { CardRowShape(132.dp, 8.dp, title = "Your playlists") }
            artistItems(artists, onOpenArtist, { list, i -> onPlay(list, i, "From your artists") }, onPickArtists)
            mostPlayedShape()
            item { LoadingShapes { SectionTitle("Keep listening"); LoadingBox(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(86.dp), RoundedCornerShape(20.dp)) } }
            // the moods depend on how much was played, so they wait for the read
            return@LazyColumn
        }
        // both are in the bottom bar too, and with nothing in them they'd open empty pages
        if (savedIds.isNotEmpty() || playlists.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), Arrangement.spacedBy(10.dp)) {
                Shortcut(Icons.Download, "Saved", onOpenSaved)
                Shortcut(Icons.List, "Playlists", onOpenPlaylists)
                if (savedIds.isNotEmpty()) Shortcut(Icons.Shuffle, "Shuffle", onShuffleSaved)
            }
        }
        item { listening() }
        if (otherSongs.isNotEmpty()) {
            item { SectionTitle("Recently played") }
            item {
                LazyRow(Modifier.fadeIn(fade), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(otherSongs, key = { it.value.id }) { (i, s) ->
                        RecentCard(s, { onPlay(songs, i, "Recently played") }, { onMore(songs[i]) })
                    }
                }
            }
        }
        if (otherLists.isNotEmpty()) {
            item { SectionTitle("Your playlists") }
            item {
                LazyRow(Modifier.fadeIn(fade), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(otherLists, key = { it.id }) { p -> PlaylistTile(p, covers[p.id].orEmpty(), { onOpenPlaylist(p.id) }, Modifier.width(132.dp), large = false) }
                }
            }
        }
        artistItems(artists, onOpenArtist, { list, i -> onPlay(list, i, "From your artists") }, onPickArtists, fade)
        // the start card already offers this when no artists are picked
        if (recent.isEmpty() && playlists.isEmpty() && artists.artists.isNotEmpty()) item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)) {
                ImportWay(onImport)
            }
        }
        if (!mostRead) mostPlayedShape()
        // five or nothing: a top list of two isn't a top list yet
        else if (mostPlayed.size >= 5) {
            item { SectionTitle("Most played") }
            val top = mostPlayed.map { it.toSong() }
            itemsIndexed(top, key = { _, s -> "most-" + s.id }) { i, s ->
                Row(Modifier.fadeIn(fade).padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(22.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SongRow(s, s.id in savedIds, { onPlay(top, i, "Most played") }, { onMore(s) }, Modifier.padding(start = 6.dp, end = 8.dp), localCover = mostPlayed[i].thumbPath)
                }
            }
        }
        if (songs.isNotEmpty()) {
            item { SectionTitle("Keep listening") }
            // one song queued alone: when it ends, the player adds similar songs, so this plays as a mix
            item { MixCard(recent[0], Modifier.fadeIn(fade)) { onPlay(listOf(songs[0]), 0, "Mix from ${songs[0].title}") } }
        }
        // built into the app, so they cost nothing until tapped; once there's history, Search has them
        if (recent.size < MOODS_UNTIL_PLAYED) {
            item { MoodsTitle(onSearch) }
            moodTiles(MOODS.take(if (recent.isEmpty() && artists.songs.isEmpty()) 6 else 4), onMood)
        }
    }
}

private fun LazyListScope.mostPlayedShape() {
    item { LoadingShapes { SectionTitle("Most played"); repeat(5) { LoadingRow(Modifier.padding(start = 48.dp, end = 20.dp)) } } }
}

/** Past this many songs in the history, Home has enough of your own to show. */
private const val MOODS_UNTIL_PLAYED = 5

@Composable
private fun Greeting(modifier: Modifier = Modifier) {
    val hour = LocalTime.now().hour
    val part = when (hour) { in 5..11 -> "morning"; in 12..17 -> "afternoon"; in 18..21 -> "evening"; else -> "night" }
    val accent = MaterialTheme.colorScheme.primary
    Text(buildAnnotatedString { append("Good $part"); withStyle(SpanStyle(color = accent)) { append(".") } }, modifier, style = MaterialTheme.typography.displaySmall)
}

/** [ACTIVE] is playing, or loading with the intent to play: either way the button must be able to stop it. */
private enum class HeroStatus { ACTIVE, PAUSED, FAILED, LAST_PLAYED }

/** A song as the hero. The button and the picture say what the player is doing; a failed song says so in place of the artist. */
@Composable
private fun SongHero(s: Song, status: HeroStatus, onOpen: () -> Unit, onControl: () -> Unit) {
    val active = status == HeroStatus.ACTIVE
    Hero(
        s.title, if (status == HeroStatus.FAILED) "Couldn't play" else s.artist,
        {
            CoverArt(s.id, s.thumbnailUrl, s.localThumbPath, Modifier.fillMaxSize().then(if (status == HeroStatus.PAUSED) Modifier.greyed() else Modifier))
            if (status == HeroStatus.PAUSED) Box(Modifier.fillMaxSize().background(Color(0x47000000)))
        },
        "Last played",
        if (active) Icons.Pause else Icons.Play,
        when (status) { HeroStatus.LAST_PLAYED -> "Play again"; HeroStatus.FAILED -> "Try again"; HeroStatus.PAUSED -> "Play"; else -> "Pause" },
        if (status == HeroStatus.LAST_PLAYED) "Play again" else "Open Now playing",
        onOpen, onControl, accent = active,
    )
}

/** The picture goes grey, so a paused song looks switched off at a glance. Drawn once; nothing here moves. */
private fun Modifier.greyed() = drawWithCache {
    val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(.45f) }) }
    onDrawWithContent { drawIntoCanvas { it.saveLayer(size.toRect(), paint); drawContent(); it.restore() } }
}

/** One shape in every state: a picture, a tag at the top, the words and one round button at the bottom. Grows with very large text. */
@Composable
private fun Hero(
    title: String, subtitle: String, art: @Composable () -> Unit, tag: String,
    controlIcon: ImageVector, controlLabel: String, openLabel: String, onOpen: () -> Unit, onControl: () -> Unit, accent: Boolean = false,
) {
    val shape = RoundedCornerShape(22.dp)
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp).fillMaxWidth().clip(shape)
            .clickable(onClickLabel = openLabel, onClick = onOpen),
    ) {
        Box(Modifier.matchParentSize()) { art() }
        // always dark, in both themes: the words sit on a picture
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0f to Color(0x6108090C), .34f to Color(0x1408090C), .64f to Color(0xB808090C), 1f to Color(0xEB08090C)),
            ),
        )
        Column(Modifier.fillMaxWidth().heightIn(min = 212.dp).padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 16.dp), Arrangement.SpaceBetween) {
            HeroTag(tag)
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp), Arrangement.spacedBy(14.dp), Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 30.sp), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) Text(subtitle, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodyLarge, color = Color(0xFFD3D7DD), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(if (accent) scheme.primary else Color.White)
                        .clickable(onClickLabel = controlLabel, role = Role.Button, onClick = onControl),
                    Alignment.Center,
                ) { Icon(controlIcon, controlLabel, Modifier.size(28.dp), tint = if (accent) scheme.onPrimary else Color(0xFF0C0D10)) }
            }
        }
    }
}

@Composable
private fun HeroTag(label: String) {
    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x9E08090C)).heightIn(min = 28.dp).padding(horizontal = 11.dp), Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
    }
}

@Composable
private fun HeroShape() {
    LoadingShapes { LoadingBox(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp).fillMaxWidth().height(212.dp), RoundedCornerShape(22.dp)) }
}

/** Nothing played, no artists, no playlists: says so, and what to do. */
@Composable
private fun StartCard(onSearch: () -> Unit, onImport: () -> Unit, onPickArtists: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(scheme.surface)) {
        Text("Nothing played yet", Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 2.dp), style = MaterialTheme.typography.headlineSmall)
        Text("What you play shows up here.", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        HorizontalDivider(color = scheme.outlineVariant)
        Way(Icons.Search, "Search for a song", "Any song, artist or album", onSearch)
        HorizontalDivider(color = scheme.outlineVariant)
        ImportWay(onImport)
        HorizontalDivider(color = scheme.outlineVariant)
        Way(Icons.List, "Pick your artists", "Choose artists for Home", onPickArtists)
    }
}

@Composable
private fun ImportWay(onImport: () -> Unit) = Way(Icons.Link, "Import a Spotify playlist", "Public link", onImport)

@Composable
private fun Way(icon: ImageVector, title: String, line: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(scheme.primary.copy(alpha = .16f)), Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = scheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(line, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Icon(Icons.Chevron, null, tint = scheme.onSurfaceVariant)
    }
}

@Composable
private fun MoodsTitle(onSeeAll: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Moods and genres", Modifier.weight(1f).padding(top = 12.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineSmall)
        Text(
            "See all", Modifier.clip(CircleShape).clickable(onClick = onSeeAll, role = Role.Button).padding(horizontal = 12.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        )
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
            CoverArt(s.id, s.thumbnailUrl, s.thumbPath, Modifier.size(140.dp), RoundedCornerShape(14.dp), shelf = true)
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(34.dp).clip(CircleShape).background(Color(0xB80C0D10)), Alignment.Center) {
                Icon(Icons.Play, null, Modifier.size(18.dp), tint = Color.White)
            }
        }
        Text(s.title, Modifier.padding(top = 9.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(s.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MixCard(s: SongEntity, modifier: Modifier, onPlay: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.padding(horizontal = 20.dp).fillMaxWidth().height(86.dp).clip(RoundedCornerShape(20.dp))
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

