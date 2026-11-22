package com.spotifry.desktop

import com.spotifry.music.data.lookupArtist
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.db.toEntity
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.spreadShuffle
import kotlinx.coroutines.launch
import java.time.LocalTime
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.foundation.onClick
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.spotifry.music.data.source.artAt
import com.spotifry.music.data.source.isLocal

/** How tall Home's picture is, the top row included; the hero sits on its lower part. */
val BANNER_HEIGHT = 470.dp

@Composable
fun HomePage(services: Services, go: (Route) -> Unit, list: LazyListState = rememberLazyListState(), modifier: Modifier = Modifier) {
    val lib = services.library
    val recent by lib.db.history().observeRecent().collectAsState(emptyList())
    val lists by lib.db.playlists().observePlaylists().collectAsState(emptyList())
    val covers by lib.db.playlists().observeCoverRows().collectAsState(emptyList())
    val most by lib.db.plays().observeMostPlayed(10).collectAsState(emptyList())
    val state by services.player.state.collectAsState()
    val scope = rememberCoroutineScope()
    val recentSongs = recent.map(SongEntity::toSong)
    val hero: Song? = state.current ?: recentSongs.firstOrNull()
    val others = recentSongs.filter { it.id != hero?.id }
    val hour = rememberHour { LocalTime.now().hour }
    val name by lib.settings.name.collectAsState()
    var creating by remember { mutableStateOf(false) }
    val yourArtists = rememberHomeArtists(services)
    // the parts of the day with enough plays for a mix, the one it is now first
    val listens by lib.db.listens().observeAll().collectAsState(emptyList())
    val mixes by produceState(emptyList<com.spotifry.music.data.TimeMix>(), listens, hour) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.spotifry.music.data.pickTimeMixes(com.spotifry.music.data.timeMixes(listens), hour) }
    }
    if (creating) NameDialog("New playlist", "Create", "", { creating = false }) { name -> scope.launch { go(Route.Playlist(lib.db.playlists().create(name))) }; creating = false }

    LazyColumn(modifier, state = list) {
        if (hero == null && lists.isEmpty()) item {
            Kicker("Welcome")
            Text("Nothing played yet.", fontFamily = Heading, fontSize = 56.sp, lineHeight = 58.sp, letterSpacing = (-1.5).sp, color = Palette.ink, modifier = Modifier.padding(top = 8.dp))
            Text("What you play shows up here.", color = Palette.ink2, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Import a playlist", Icons.Download, accent = true) { services.importer.open() }
            }
        }
        if (hero != null) item {
            val playingThis = state.current?.id == hero.id
            val song: @Composable () -> Unit = {
                Column {
                    // "Good evening, Ali" with a name set in Settings; without one, a short line follows the greeting
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Kicker(buildGreeting(hour).trimEnd('.') + (name.trim().takeIf { it.isNotEmpty() }?.let { ", $it" } ?: ""))
                        if (name.isBlank()) Box(Modifier.padding(start = 14.dp).width(70.dp).height(1.dp).background(Palette.ink.copy(alpha = 0.3f)))
                    }
                    BrushTitle(hero.title, Modifier.padding(top = 10.dp))
                    Text(hero.artist, color = Palette.ink.copy(alpha = 0.85f), fontSize = 20.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 26.dp))
                    Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Pill(if (playingThis && state.wantsPlay) "Pause" else if (playingThis) "Play" else "Play again", if (playingThis && state.wantsPlay) Icons.Pause else Icons.Play, accent = true) {
                            if (playingThis) services.player.toggle() else services.player.playList(listOf(hero), 0, "Home")
                        }
                        if (recentSongs.size > 1) Pill("Shuffle", Icons.Shuffle, glass = true) { services.player.shuffleList(recentSongs, "Recently played") }
                        if (!hero.isLocal) Pill("Save", Icons.Download, glass = true) { scope.launch { lib.downloads.save(hero) } }
                    }
                }
            }
            // the song on the picture with your rotation beside it; on a narrow window the rotation goes under the song
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 30.dp)) {
                if (maxWidth > 980.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f).padding(top = 12.dp)) { song() }
                    RotationCard(services, Modifier.width(470.dp), glass = true, onSpotifried = { go(Route.Spotifried) }) { go(Route.Rotation) }
                } else Column {
                    song()
                    RotationCard(services, Modifier.padding(top = 28.dp).fillMaxWidth(), glass = true, onSpotifried = { go(Route.Spotifried) }) { go(Route.Rotation) }
                }
            }
        }
        if (others.isNotEmpty()) {
            item { HomeSection("Recently played", { savedFilter = com.spotifry.music.ui.saved.SavedFilter.Lately; go(Route.Saved) }) }
            item {
                SlideRow(arrowTop = COVER_ARROW) {
                    itemsIndexed(others.take(12)) { i, s ->
                        CoverTile(s.thumbnailUrl ?: s.localThumbPath, s.title, s.artist) { services.player.playList(others, i, "Recently played") }
                    }
                }
            }
        }
        val forYou = lib.forYou
        if (forYou != null) item { ForYouRow(services, forYou) }
        if (hero != null || lists.isNotEmpty()) {
            item { HomeSection("Your playlists", { go(Route.Playlists) }) }
            item {
                // five at most: See all opens the rest
                SlideRow(arrowTop = 56.dp) {
                    itemsIndexed(lists.take(5)) { _, p ->
                        // the chosen cover, else the first song's; the tile is wide, so no mosaic here
                        val custom = remember(p.cover) { lib.playlistCovers.file(p.cover)?.path }
                        val art = custom ?: covers.filter { it.playlistId == p.id }.firstNotNullOfOrNull { it.song.thumbnailUrl ?: it.song.thumbPath }
                        Tile(art, onClick = { go(Route.Playlist(p.id)) }) {
                            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                                Text(p.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text("${p.songCount} ${if (p.songCount == 1) "song" else "songs"}", fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f))
                            }
                        }
                    }
                    item { Tile(onClick = { creating = true }) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Plus, null, Modifier.size(30.dp), tint = Palette.ink)
                            Text("Create playlist", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    } }
                }
            }
        }
        // five or nothing: a top list of two isn't a top list yet; two columns, read across
        if (most.size >= 5) {
            item { HomeSection("Most played", null) }
            val top = most.map(SongEntity::toSong)
            itemsIndexed(top.indices.chunked(2), key = { _, pair -> "most-" + top[pair[0]].id }) { _, pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    for (i in 0 until 2) Box(Modifier.weight(1f)) {
                        pair.getOrNull(i)?.let { k -> SongLine(services, null, top[k], state.current?.id == top[k].id, saved = most[k].isSaved) { services.player.playList(top, k, "Most played") } }
                    }
                }
            }
        }
        // the artists picked for Home, then a few songs from each, as on the phone
        if (yourArtists.artists.isNotEmpty()) {
            item { HomeSection("Your artists", { go(Route.Artists) }, "Edit") }
            item {
                SlideRow(arrowTop = 52.dp) {
                    itemsIndexed(yourArtists.artists, key = { _, a -> a.key }) { _, a ->
                        ArtistCircle(a) {
                            val link = if (a.url.isNotBlank()) a.link else null
                            if (link != null) go(Route.Found(link, Route.Home)) else scope.launch {
                                runCatching { services.source.lookupArtist(a.name) }.getOrNull()?.let { go(Route.Found(it, Route.Home)) }
                            }
                        }
                    }
                }
            }
            if (yourArtists.songs.isNotEmpty()) {
                item { HomeSection("From your artists", null) }
                item {
                    SlideRow(arrowTop = COVER_ARROW) {
                        itemsIndexed(yourArtists.songs, key = { _, s -> "artist-" + s.id }) { i, s ->
                            CoverTile(s.thumbnailUrl ?: s.localThumbPath, s.title, s.artist) { services.player.playList(yourArtists.songs, i, "Your artists") }
                        }
                    }
                }
            }
        } else if (hero != null) item { Box(Modifier.padding(top = 22.dp)) { Pill("Pick artists for Home", Icons.Plus, glass = true) { go(Route.Artists) } } }
        // a mix from the last song, then the songs you play most at a time of day, as on the phone's "Keep listening"
        if (mixes.isNotEmpty() || recentSongs.isNotEmpty()) {
            item { HomeSection("Keep listening", null) }
            item {
                SlideRow(arrowTop = COVER_ARROW) {
                    // one song queued alone: when it ends, the player adds similar songs, so this plays as a mix
                    recentSongs.firstOrNull()?.let { last ->
                        val art = last.thumbnailUrl ?: last.localThumbPath
                        item { MixCard(null, listOfNotNull(art), "Mix from", last.title, "then similar songs", wide = false) { services.player.playList(listOf(last), 0, "Mix from ${last.title}") } }
                    }
                    itemsIndexed(mixes) { _, m ->
                        val songs = m.songs.map(SongEntity::toSong)
                        val word = if (m.kind == "Night") "Late nights" else m.kind + "s"
                        MixCard(m.kind, songs.mapNotNull { it.thumbnailUrl ?: it.localThumbPath }.take(3), "Your", word, MIX_HOURS[m.kind] ?: "", wide = m.kind == com.spotifry.music.data.hourKind(hour)) { services.player.playList(songs, 0, m.title) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp + LocalBottomInset.current)) }
    }
}

/**
 * The song's name, large, on one line, with a hand-drawn accent stroke under it. A name too long for the room slides
 * slowly sideways, pausing at the start, and fades out at the right edge instead of being cut.
 */
@Composable
private fun BrushTitle(title: String, modifier: Modifier = Modifier) {
    var textWidth by remember(title) { mutableStateOf(0f) }
    var baseline by remember(title) { mutableStateOf(0f) }
    var room by remember { mutableStateOf(0f) }
    val size = if (title.length <= 14) 84.sp else 64.sp
    val accent = Palette.accent
    val long = textWidth > room && room > 0f
    Box(
        modifier.fillMaxWidth().onSizeChanged { room = it.width.toFloat() }.drawBehind {
            if (textWidth <= 0f) return@drawBehind
            // a brush stroke: a round, thick start on the left, sweeping up to the right and thinning to a point
            val w = minOf(minOf(textWidth, room) * .82f, 560.dp.toPx())
            val thick = 11.dp.toPx(); val x0 = -4.dp.toPx(); val y0 = baseline + 14.dp.toPx(); val rise = 9.dp.toPx()
            fun center(s: Float) = androidx.compose.ui.geometry.Offset(x0 + w * s, y0 - rise * s * s + 2.dp.toPx() * s * (1 - s) * 4)
            fun width(s: Float) = thick * (1f - s).let { it * (2f - it) }.coerceAtLeast(0.04f)
            val n = 48
            val top = (0..n).map { i -> val s = i / n.toFloat(); center(s).let { it.copy(y = it.y - width(s) / 2) } }
            val bottom = (0..n).map { i -> val s = i / n.toFloat(); center(s).let { it.copy(y = it.y + width(s) / 2) } }
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(top[0].x, top[0].y)
                top.drop(1).forEach { lineTo(it.x, it.y) }
                bottom.reversed().forEach { lineTo(it.x, it.y) }
                close()
            }
            val paint = Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.85f)), startX = x0, endX = x0 + w)
            drawPath(path, paint)
            drawCircle(accent, thick / 2, center(0f))
        },
    ) {
        Text(
            title, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = size, letterSpacing = (-2).sp, color = Palette.ink, maxLines = 1, softWrap = false,
            onTextLayout = { textWidth = it.multiParagraph.intrinsics.maxIntrinsicWidth; baseline = it.firstBaseline },
            modifier = Modifier.fillMaxWidth()
                .then(if (long) Modifier
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithContent { drawContent(); drawRect(Brush.horizontalGradient(0.88f to Color.Black, 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn) }
                    .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2500, repeatDelayMillis = 2500, spacing = androidx.compose.foundation.MarqueeSpacing(80.dp), velocity = 45.dp)
                else Modifier),
        )
    }
}

/** A section's name with an accent mark before it, and See all at the far end. */
@Composable
private fun HomeSection(title: String, onAll: (() -> Unit)?, allLabel: String = "See all →") = Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(Palette.accent))
    Text(title, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = Palette.ink, modifier = Modifier.padding(start = 12.dp).weight(1f))
    if (onAll != null) Text(allLabel, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink2, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onAll).padding(horizontal = 6.dp, vertical = 4.dp))
}

/** Song covers on Home are 180 square; the side arrows sit on their middle. */
private val COVER = 180.dp
private val COVER_ARROW = COVER / 2 - 22.dp

/** A song's cover with its name and artist on it, over a dark fade on the lower 30% so they read on any picture. */
@Composable
private fun CoverTile(art: String?, title: String, subtitle: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hot by src.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hot) 1.04f else 1f, tween(200))
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.size(COVER).graphicsLayer { scaleX = lift; scaleY = lift }.clip(shape).background(Palette.ink.copy(alpha = 0.06f)).hoverable(src).clickable(src, null, onClick = onClick)) {
        CoverFill(art, Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(.3f).background(Brush.verticalGradient(
            0f to Color.Transparent, .133f to Color.Black.copy(alpha = .5f), .4f to Color.Black.copy(alpha = .78f), 1f to Color.Black.copy(alpha = .92f),
        )))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
            Text(title, fontFamily = Display, fontSize = 14.sp, lineHeight = 18.sp, color = Color.White, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = Color.White.copy(alpha = .8f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/** The hours each part of the day covers, as [com.spotifry.music.data.hourKind] splits them. */
private val MIX_HOURS = mapOf("Morning" to "5 a.m. – noon", "Afternoon" to "noon – 6 p.m.", "Evening" to "6 – 10 p.m.", "Night" to "10 p.m. – 5 a.m.")

/**
 * A Keep listening mix: that part of the day's Home picture drifting slowly behind, washed in the colour of the mix's
 * covers, with three of its covers stacked and tilted on the right. The picture and each cover follow the pointer by a
 * different amount, so the card has depth, and the covers fan out under the pointer. [kind] null is the mix from your
 * last song: its own cover, blurred, is the picture, and the two covers behind are blurred copies of it.
 */
@Composable
private fun MixCard(kind: String?, covers: List<String>, small: String, name: String, line: String, wide: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hot by src.collectIsHoveredAsState()
    var pointer by remember { mutableStateOf(Offset.Zero) }
    val px by animateFloatAsState(pointer.x, tween(600)); val py by animateFloatAsState(pointer.y, tween(600))
    val fan by animateFloatAsState(if (hot) 1f else 0f, tween(450))
    val drift by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(38_000, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse))
    val first = covers.firstOrNull()
    val tint by produceState(Color(0xFF2A2A2E), first) { first?.let { c -> Covers.load(artAt(c, false))?.tint() }?.let { value = androidx.compose.ui.graphics.lerp(it, Color.Black, .2f) } }
    val picture = remember(kind) { kind?.let { bannerImage(it.lowercase()) } }
    val shape = RoundedCornerShape(16.dp)
    // the covers are smaller on a narrow card, and the words keep to the room left of them
    val k = if (wide) 1f else .8f
    val cardWidth = if (wide) 378.dp else 280.dp
    val textRoom = cardWidth - 18.dp - 132.dp * k - 18.dp - 10.dp
    Box(
        Modifier.size(cardWidth, COVER).clip(shape).background(tint).hoverable(src).clickable(src, null, onClick = onClick)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        pointer = if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Exit) Offset.Zero
                        else e.changes.first().position.let { Offset(it.x / size.width * 2 - 1, it.y / size.height * 2 - 1) }
                    }
                }
            },
    ) {
        // the picture, a little larger than the card so it can drift and shift without showing an edge
        Box(Modifier.matchParentSize().graphicsLayer {
            val z = if (kind != null) 1.04f + .08f * drift else 1.1f
            scaleX = z; scaleY = z
            translationX = (-6 * px - 14 * drift * (if (kind != null) 1 else 0)).dp.toPx(); translationY = (-4 * py - 6 * drift * (if (kind != null) 1 else 0)).dp.toPx()
        }) {
            if (picture != null) androidx.compose.foundation.Image(picture, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else if (kind == null) CoverFill(first, Modifier.fillMaxSize().blur(16.dp))
        }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to tint.copy(alpha = .92f), .48f to tint.copy(alpha = .55f), .8f to tint.copy(alpha = 0f))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(.4f to Color.Transparent, 1f to Color.Black.copy(alpha = .35f))))
        // three covers, back to front: each further back is turned more, darker, and moves less with the pointer
        Box(Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 18.dp).size(132.dp * k, 136.dp * k)) {
            val art = if (kind == null) List(3) { first } else List(3) { covers.getOrNull(it) ?: first }
            data class Layer(val x: Float, val y: Float, val turn: Float, val move: Float, val dim: Float, val fx: Float, val fy: Float, val fTurn: Float)
            val layers = listOf(Layer(28f, 0f, 9f, 3f, .3f, 16f, -6f, 7f), Layer(14f, 10f, 4f, 5f, .15f, 8f, -3f, 4f), Layer(0f, 28f, -3f, 8f, 0f, -4f, 0f, -3f))
            layers.forEachIndexed { i, l ->
                val back = kind == null && i < 2
                Box(Modifier.offset(l.x.dp * k, l.y.dp * k).size(104.dp * k).graphicsLayer {
                    translationX = (l.move * px + l.fx * fan).dp.toPx(); translationY = (l.move * .62f * py + l.fy * fan).dp.toPx(); rotationZ = l.turn + l.fTurn * fan
                }.shadow(14.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))) {
                    CoverFill(art[2 - i], Modifier.fillMaxSize().then(if (back) Modifier.blur(3.dp) else Modifier))
                    val dim = if (back) .4f else l.dim
                    if (dim > 0f) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = dim)))
                }
            }
        }
        Column(Modifier.fillMaxHeight().padding(start = 18.dp, top = 18.dp, bottom = 16.dp).width(textRoom)) {
            Text(small, fontSize = 12.sp, letterSpacing = .5.sp, color = Color.White.copy(alpha = .78f))
            if (kind == null) Text(name, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, lineHeight = 22.sp, letterSpacing = (-.6).sp, color = Color.White, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            else {
                // as large as fits: a long name like "Late nights" steps down instead of running under the covers
                var fit by remember(name, wide) { mutableStateOf(if (wide) 34f else 28f) }
                Text(
                    name, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = fit.sp, lineHeight = (fit + 2).sp, letterSpacing = (-.8).sp, color = Color.White,
                    maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                    onTextLayout = { if (it.didOverflowWidth && fit > 18f) fit -= 2f },
                )
            }
            Text(line, fontSize = 12.sp, color = Color.White.copy(alpha = .75f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.weight(1f))
            val lift by animateFloatAsState(if (hot) 1.06f else 1f, tween(200))
            Box(Modifier.size(40.dp).graphicsLayer { scaleX = lift; scaleY = lift }.clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Icon(Icons.Play, null, Modifier.size(20.dp), tint = Color(0xFF111111))
            }
        }
    }
}

/** A wide playlist tile: a darkened cover behind, or a plain glass face for Create playlist. */
@Composable
private fun Tile(art: String? = null, onClick: () -> Unit, size: androidx.compose.ui.unit.DpSize = androidx.compose.ui.unit.DpSize(300.dp, 156.dp), content: @Composable BoxScope.() -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hot by src.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hot) 1.03f else 1f, tween(200))
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, art) { value = art?.let { Covers.load(artAt(it, true)) } }
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.size(size).graphicsLayer { scaleX = lift; scaleY = lift }.clip(shape).background(Palette.ink.copy(alpha = 0.06f))
            .border(1.dp, Palette.ink.copy(alpha = if (hot) 0.22f else 0.1f), shape).hoverable(src).clickable(src, null, onClick = onClick),
    ) {
        image?.let {
            androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.75f)))))
        }
        content()
    }
}

/**
 * Home's picture, behind the top row and the song: one for the morning, afternoon, evening and night, from
 * resources/banner/<time>.jpg. Dark on the left and at the bottom so the words read, and it scrolls away with the page.
 * Without a picture there is only a soft glow.
 */
@Composable
fun HomeBanner(services: Services, list: LazyListState, base: Color, modifier: Modifier = Modifier) {
    val slot = when (rememberHour(bannerHour)) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..20 -> "evening"; else -> "night" }
    val own by services.library.homePicture.collectAsState()
    val image by produceState(remember(slot) { bannerImage(slot) }, own, slot) {
        value = own?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ownPicture(it) } } ?: bannerImage(slot)
    }
    val shift by remember { derivedStateOf { if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else Float.MAX_VALUE } }
    Box(
        modifier.fillMaxWidth().height(BANNER_HEIGHT)
            .graphicsLayer { translationY = -shift.coerceAtMost(size.height); alpha = (1f - shift / size.height).coerceIn(0f, 1f); compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            // the lower part melts into the window instead of ending in a line
            .drawWithContent { drawContent(); drawRect(Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn) },
    ) {
        val shown = image
        if (shown != null) androidx.compose.foundation.Image(shown, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop, alignment = Alignment.CenterEnd)
        else Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Palette.accent.copy(alpha = 0.22f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(1100f, 120f), radius = 900f)))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to base.copy(alpha = 0.92f), 0.4f to base.copy(alpha = 0.55f), 0.75f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to base.copy(alpha = 0.7f), 0.24f to Color.Transparent)))
    }
}

/** A picture chosen in Settings, made 2400 wide by ffmpeg so a camera photo stays light; null when it can't be read. */
private val own = HashMap<String, androidx.compose.ui.graphics.ImageBitmap?>()
private fun ownPicture(path: String) = synchronized(own) { own.getOrPut(path) {
    runCatching {
        val bytes = runCatching {
            val p = ProcessBuilder(Platform.tool("ffmpeg"), "-v", "quiet", "-i", path, "-frames:v", "1", "-vf", "scale='min(2400,iw)':-2", "-f", "image2pipe", "-c:v", "mjpeg", "-q:v", "3", "-")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            p.inputStream.readBytes().takeIf { p.waitFor() == 0 && it.isNotEmpty() }
        }.getOrNull() ?: java.io.File(path).readBytes()
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
} }

/** [now]'s hour, read again every minute, so a page left open overnight follows the clock. */
@Composable
private fun rememberHour(now: () -> Int): Int = produceState(now()) { while (true) { kotlinx.coroutines.delay(60_000); value = now() } }.value

/** The hour that picks Home's picture; tests set it to see each one. */
internal var bannerHour: () -> Int = { LocalTime.now().hour }

/** The picture for one time of day, read once; null until one is added to the app. */
private val banners = HashMap<String, androidx.compose.ui.graphics.ImageBitmap?>()
private fun bannerImage(slot: String) = banners.getOrPut(slot) {
    runCatching {
        Palette::class.java.getResourceAsStream("/banner/$slot.jpg")?.use { org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
    }.getOrNull()
}

fun buildGreeting(hour: Int) = when { hour < 12 -> "Good morning."; hour < 18 -> "Good afternoon."; else -> "Good evening." }

/**
 * A row of covers that slides sideways. Shift and the mouse wheel, or a touchpad, move it; round arrows at either end show
 * when there is more that way, so nobody has to know that.
 */
@Composable
private fun SlideRow(arrowTop: androidx.compose.ui.unit.Dp, content: LazyListScope.() -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    BoxWithConstraints {
        val step = with(androidx.compose.ui.platform.LocalDensity.current) { (maxWidth * .8f).toPx() }
        // room around the row for a cover or tile that grows a little under the pointer; the row is widened by the same
        // amount on both sides so the first cover still lines up with the titles
        val room = 10.dp
        LazyRow(
            Modifier.layout { m, c ->
                val p = room.roundToPx()
                val placed = m.measure(c.copy(minWidth = c.minWidth + 2 * p, maxWidth = if (c.hasBoundedWidth) c.maxWidth + 2 * p else c.maxWidth))
                layout(placed.width - 2 * p, placed.height) { placed.place(-p, 0) }
            },
            state = list, contentPadding = PaddingValues(room), horizontalArrangement = Arrangement.spacedBy(18.dp), content = content,
        )
        // on the middle of the covers or tiles
        Slide(list.canScrollBackward, left = true, Modifier.align(Alignment.TopStart).padding(top = arrowTop + room).offset(x = (-22).dp)) { scope.launch { list.animateScrollBy(-step) } }
        Slide(list.canScrollForward, left = false, Modifier.align(Alignment.TopEnd).padding(top = arrowTop + room).offset(x = 22.dp)) { scope.launch { list.animateScrollBy(step) } }
    }
}

@Composable
private fun Slide(shown: Boolean, left: Boolean, modifier: Modifier, onClick: () -> Unit) =
    AnimatedVisibility(shown, modifier, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.bg.copy(alpha = .82f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Chevron(left)
        }
    }

@Composable
internal fun Chevron(left: Boolean) {
    val ink = Palette.ink
    Canvas(Modifier.size(14.dp)) {
        val w = size.width; val x0 = if (left) w * .68f else w * .32f; val x1 = if (left) w * .3f else w * .7f
        drawLine(ink, Offset(x0, 0f), Offset(x1, w / 2), 2.dp.toPx(), StrokeCap.Round)
        drawLine(ink, Offset(x1, w / 2), Offset(x0, w), 2.dp.toPx(), StrokeCap.Round)
    }
}

/**
 * For you: suggestions from your listening. Hovering or clicking the row holds a new list back until Home is left, so
 * it never changes under the pointer. Right-click a tile for the song's menu, with "Not this song".
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ForYouRow(services: Services, shelf: com.spotifry.music.data.ForYouShelf) {
    val list by shelf.shown.collectAsState()
    DisposableEffect(shelf) { shelf.homeVisible(true); onDispose { shelf.homeVisible(false) } }
    val songs = list ?: return
    if (songs.isEmpty()) return
    Column(Modifier.onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) { shelf.rowTouched() }) {
        HomeSection("For you", null)
        SlideRow(arrowTop = COVER_ARROW) {
            itemsIndexed(songs, key = { _, s -> "fy-" + s.id }) { i, s ->
                var menu by remember { mutableStateOf(false) }
                Box(Modifier.onClick(matcher = androidx.compose.foundation.PointerMatcher.mouse(androidx.compose.ui.input.pointer.PointerButton.Secondary)) { menu = true }) {
                    CoverTile(s.thumbnailUrl ?: s.localThumbPath, s.title, s.artist) { shelf.rowTouched(); services.player.playList(songs, i, "Recommendation") }
                    if (menu) SongMenu(services, s, { menu = false }) { close ->
                        androidx.compose.material3.DropdownMenuItem({ Text("Not this song") }, onClick = { services.library.setNotSuggested(s.id, true); close() })
                    }
                }
            }
        }
    }
}
