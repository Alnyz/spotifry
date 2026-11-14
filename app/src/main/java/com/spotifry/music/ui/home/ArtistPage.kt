package com.spotifry.music.ui.home

import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.R
import com.spotifry.music.data.PickedArtist
import com.spotifry.music.data.source.PageContent
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.clockText
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.Cover
import com.spotifry.music.ui.components.ErrorRow
import com.spotifry.music.ui.components.LoadingBox
import com.spotifry.music.ui.components.LoadingRow
import com.spotifry.music.ui.components.LoadingShapes
import com.spotifry.music.ui.components.LocalImageClient
import com.spotifry.music.ui.components.LocalNowPlaying
import com.spotifry.music.ui.components.PlayingBars
import com.spotifry.music.ui.components.coverBitmap
import com.spotifry.music.ui.components.tintOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private val Bg = Color(0xFF0C0D10)
private val Glass = Color.White.copy(alpha = .1f)

/** The serif the app uses for names and headings (the onboarding names, the stats). */
@OptIn(ExperimentalTextApi::class)
private val Bodoni = FontFamily(Font(R.font.bodonimoda, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600), FontVariation.Setting("opsz", 28f))))

/** The songs you played come first, most played first; the rest keep the order they came in. */
internal fun topSongsOrder(songs: List<Song>, counts: Map<String, Int>): List<Song> =
    songs.withIndex().sortedWith(compareByDescending<IndexedValue<Song>> { counts[it.value.id] ?: 0 }.thenBy { it.index }).map { it.value }

/** The artist's photo asked for at 1080 px: YouTube's picture servers resize on request, so this is real detail, not a stretched small one. */
internal fun heroArt(url: String?): String? = url?.replaceFirst(Regex("=(w\\d+-h\\d+|s\\d+)"), "=w1080-h1080")

/** The page colour under the photo: the photo's colour taken a little darker, or a quiet grey before the photo has loaded. */
internal fun pageTint(fromPhoto: Color?): Color = fromPhoto?.let { lerp(it, Bg, .3f) } ?: Color(0xFF1C1F26)

/** The star: adds the artist to Your artists, or takes it out when it is already there. */
internal fun toggleArtist(list: List<PickedArtist>, link: PageLink): List<PickedArtist> {
    val key = link.name.trim().lowercase()
    return if (list.any { it.key == key }) list.filter { it.key != key } else list + PickedArtist(link.name, link.url, link.thumbnailUrl)
}

internal fun playsText(n: Int) = "$n ${if (n == 1) "play" else "plays"}"

/** "Album · 12 songs", or just "Album" when the search didn't say how many. */
internal fun albumLine(a: PageLink) = "Album" + (a.songs?.let { " · $it ${if (it == 1) "song" else "songs"}" } ?: "")

private fun fetchHero(client: OkHttpClient?, url: String?): ImageBitmap? {
    val big = heroArt(url) ?: return null
    return runCatching {
        client?.newCall(Request.Builder().url(big).build())?.execute()?.use { r ->
            if (!r.isSuccessful) return@use null
            val bytes = r.body?.bytes() ?: return@use null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }.getOrNull()
}

/**
 * An artist: the photo over the top half of the screen with the name on it, then Shuffle, Play and the star, one album,
 * the songs you play most, and the albums. Back and the menu sit on the photo and scroll away with it.
 */
@Composable
internal fun ArtistPage(
    link: PageLink, p: PageContent?, failed: Boolean, counts: Map<String, Int>, starred: Boolean, onStar: () -> Unit,
    onBack: () -> Unit, onPlay: (List<Song>, Int, String) -> Unit, onShuffle: (List<Song>, String) -> Unit, onMore: (Song) -> Unit,
    actions: PageActions, bottomInset: Dp, onRetry: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val client = LocalImageClient.current
    val files = LocalContext.current.contentResolver
    val name = p?.name ?: link.name
    val photoUrl = p?.thumbnailUrl ?: link.thumbnailUrl
    val photo by produceState<ImageBitmap?>(null, photoUrl, client) {
        value = withContext(Dispatchers.IO) { fetchHero(client, photoUrl) } ?: coverBitmap(client, photoUrl, null, files)?.asImageBitmap()
    }
    val tint = remember(photo) { pageTint(photo?.let { tintOf(it.asAndroidBitmap()) }) }
    val songs = p?.songs.orEmpty()
    val ordered = remember(songs, counts) { topSongsOrder(songs, counts) }
    var allSongs by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val list = rememberLazyListState()
    val heroH = LocalConfiguration.current.screenHeightDp.dp * .55f
    val heroPx = with(density) { heroH.toPx() }
    val statusPx = WindowInsets.statusBars.getTop(density)
    val statusH = with(density) { statusPx.toDp() }

    // the screen hands this page the whole height, status bar included, so the photo starts at the very top
    Box(Modifier.fillMaxSize().background(Bg)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list, contentPadding = PaddingValues(bottom = bottomInset + 16.dp),
        ) {
            item {
                Box(Modifier.fillMaxWidth().height(heroH)) {
                    val img = photo
                    if (img != null) Image(img, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) else Box(Modifier.matchParentSize().background(tint))
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(colorStops = arrayOf(0f to Bg.copy(alpha = .4f), .2f to Color.Transparent, .4f to Color.Transparent, .55f to tint.copy(alpha = .22f), .7f to tint.copy(alpha = .55f), .85f to tint.copy(alpha = .86f), 1f to tint))))
                    // Back and the menu belong to the photo: they scroll away with it
                    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = statusH + 4.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        val pill = Bg.copy(alpha = .55f)
                        RoundButton(onBack, true, 48.dp, pill) { Icon(Icons.Back, "Back", Modifier.size(22.dp)) }
                        Box {
                            RoundButton({ menu = true }, true, 48.dp, pill) { Icon(Icons.More, "More options", Modifier.size(22.dp)) }
                            PageMenu(menu, { menu = false }, link, actions, null)
                        }
                    }
                    Text(
                        name, Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 22.dp), color = Color.White,
                        fontFamily = Bodoni, fontWeight = FontWeight.SemiBold, fontSize = 50.sp, lineHeight = 52.sp, letterSpacing = (-1.25).sp,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item {
                val ready = songs.isNotEmpty()
                Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(tint, Bg))).padding(top = 6.dp, bottom = 22.dp), Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally), Alignment.CenterVertically) {
                    RoundButton({ onShuffle(songs, name) }, ready, 52.dp, Glass) { Icon(Icons.Shuffle, "Shuffle", Modifier.size(23.dp)) }
                    RoundButton({ onPlay(ordered, 0, name) }, ready, 88.dp, scheme.primary, scheme.onPrimary) { Icon(Icons.Play, "Play", Modifier.size(38.dp)) }
                    RoundButton(onStar, true, 52.dp, Glass) {
                        Icon(if (starred) Icons.Star else Icons.StarOutline, if (starred) "Remove from your artists" else "Add to your artists", Modifier.size(23.dp))
                    }
                }
            }
            when {
                p == null && !failed -> item {
                    LoadingShapes(Modifier.padding(start = 20.dp, end = 8.dp)) {
                        // the real heading, unseen, so the first song lands where the first shape is at any text size
                        Box(Modifier.padding(top = 28.dp, bottom = 8.dp)) {
                            LoadingBox(Modifier.matchParentSize(), RoundedCornerShape(6.dp))
                            Text("Top songs", Modifier.clearAndSetSemantics {}, Color.Transparent, fontFamily = Bodoni, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-.26).sp)
                        }
                        repeat(5) { LoadingRow() }
                    }
                }
                p == null -> item { ErrorRow(link.name, onRetry, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                else -> {
                    p.albums.firstOrNull()?.let { a -> item { AlbumCard(a, actions) } }
                    if (songs.isNotEmpty()) item { Heading("Top songs") }
                    val shown = if (allSongs) ordered else ordered.take(ARTIST_SONGS)
                    itemsIndexed(shown) { i, s -> TopSongRow(s, counts[s.id] ?: 0, { onPlay(ordered, i, name) }, { onMore(s) }) }
                    if (shown.size < ordered.size) item {
                        Text(
                            "Show all ${ordered.size} songs", Modifier.fillMaxWidth().clickable { allSongs = true }.padding(horizontal = 20.dp, vertical = 14.dp),
                            style = MaterialTheme.typography.titleSmall, color = scheme.primary,
                        )
                    }
                    if (p.albums.isNotEmpty()) item {
                        Heading("Albums")
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(p.albums) { a ->
                                Column(Modifier.width(132.dp).clickable(onClickLabel = "Open album") { actions.open(a) }) {
                                    Cover(a.url, a.thumbnailUrl, null, 132.dp, radius = 14.dp)
                                    Text(a.name, Modifier.padding(top = 9.dp), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    a.songs?.let { Text("$it ${if (it == 1) "song" else "songs"}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                    if (songs.isEmpty() && p.albums.isEmpty()) item {
                        Text("Nothing to play here.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }

        // the slim row has no strip behind it: rows fade out as they reach it (above), so nothing dark is drawn on the page
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, enabled: Boolean, size: Dp, fill: Color, ink: Color = Color.White, icon: @Composable () -> Unit) {
    // the circle is the button itself, so the tap area is the circle and nothing around it is drawn
    Box(
        Modifier.size(size).clip(CircleShape).background(fill).clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        Alignment.Center,
    ) { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides if (enabled) ink else ink.copy(alpha = .4f)) { icon() } }
}

@Composable
private fun Heading(text: String) =
    Text(text, Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 8.dp), fontFamily = Bodoni, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-.26).sp)

/** The first album, on glass; the plus does what Save as playlist does on an album page. */
@Composable
private fun AlbumCard(a: PageLink, actions: PageActions) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(shape).background(Color.White.copy(alpha = .07f)).border(1.dp, Color.White.copy(alpha = .06f), shape)
            .clickable(onClickLabel = "Open album") { actions.open(a) }.padding(12.dp),
        Arrangement.spacedBy(14.dp), Alignment.CenterVertically,
    ) {
        Cover(a.url, a.thumbnailUrl, null, 76.dp, radius = 15.dp)
        Column(Modifier.weight(1f)) {
            Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(albumLine(a), Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RoundButton({ actions.save(a, null) }, true, 44.dp, Glass) { Icon(Icons.Plus, "Save as playlist", Modifier.size(22.dp)) }
    }
}

/** A top song: its plays in the accent colour (when it has any) and its length; the one playing has moving bars over its cover. */
@Composable
private fun TopSongRow(s: Song, plays: Int, onClick: () -> Unit, onMore: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val now = LocalNowPlaying.current
    val current = now != null && now.id == s.id
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = if (current) now!!.open else onClick).padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        Arrangement.spacedBy(14.dp), Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp)) {
            Cover(s.id, s.thumbnailUrl, s.localThumbPath, 48.dp, radius = 9.dp)
            if (current) Box(Modifier.matchParentSize().clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(alpha = .5f)), contentAlignment = Alignment.Center) { PlayingBars(now!!.playing) }
        }
        Column(Modifier.weight(1f)) {
            Text(
                s.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = if (current) scheme.primary else Color.Unspecified,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildAnnotatedString {
                    if (plays > 0) { withStyle(SpanStyle(color = scheme.primary)) { append(playsText(plays)) }; append(" · ") }
                    append(clockText(s.durationSec * 1000))
                },
                Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onMore, Modifier.size(48.dp)) { Icon(Icons.More, "More options") }
    }
}
