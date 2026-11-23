package com.spotifry.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.source.RadioStation
import com.spotifry.music.data.source.isRadio
import com.spotifry.music.data.source.toSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** Every country, by name in the computer's language, with the code radio-browser.info uses. A country with no stations just shows an empty list. */
val RADIO_COUNTRIES: List<Pair<String, String>> = java.util.Locale.getISOCountries()
    .map { java.util.Locale.Builder().setRegion(it).build().displayCountry to it }
    .filter { (name, code) -> name.isNotBlank() && name != code }
    .sortedBy { it.first }

/** Genres offered in the filter: the tag stations are listed under. As on the phone. */
val RADIO_GENRES = listOf("pop", "rock", "hip hop", "dance", "electronic", "jazz", "classical", "country", "chill", "news")

/** The mixes on the page, in this order; "Made for you" comes first when there is one. As on the phone. */
private val NONSTOP = listOf(ALL_DAY) + MOODS.filter { it.name in setOf("Chill", "Focus", "Workout") } + NIGHT_DRIVE

/** Plays a station, and tells the list it was played (that is how stations get to be popular for everyone). */
fun playStation(services: Services, s: RadioStation) {
    services.player.playList(listOf(s.toSong()), 0, "Radio")
    services.scope.launch { services.library.radio.click(s.uuid) }
}

/** The first letters of the first two words of a station's name, in capitals; empty when the name has no letters. */
internal fun stationInitials(name: String?): String = name.orEmpty().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.take(2)
    .joinToString("") { it.substring(0, it.offsetByCodePoints(0, 1)).uppercase() }

/** A station's logo; with none (or one that won't load) its first letters on a flat square. */
@Composable
fun StationCover(name: String, logo: String?, size: Dp, shape: Shape, modifier: Modifier = Modifier) {
    val loaded by produceState(false to (null as ImageBitmap?), logo) { value = false to null; value = true to logo?.let { Covers.load(it) } }
    val img = loaded.second
    Box(modifier.size(size).clip(shape).background(Palette.high), Alignment.Center) {
        if (img != null) Image(img, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        else if (logo == null || loaded.first) {
            val letters = stationInitials(name)
            if (letters.isEmpty()) Icon(Icons.Radio, null, Modifier.size(size * .46f), tint = Palette.ink2)
            else {
                Text(letters, fontFamily = Heading, fontSize = (size.value * .34f).sp, color = Palette.ink, maxLines = 1)
                if (size >= 96.dp) Icon(Icons.Radio, null, Modifier.align(Alignment.BottomEnd).padding(size * .06f).size(size * .2f), tint = Palette.ink2)
            }
        }
    }
}

/** A song's cover; a station's logo (or its first letters) for radio. */
@Composable
fun SongCover(song: com.spotifry.music.data.source.Song, size: Dp, shape: Shape, large: Boolean = false, modifier: Modifier = Modifier) =
    if (song.isRadio) StationCover(song.title, song.thumbnailUrl, size, shape, modifier) else Cover(song.thumbnailUrl ?: song.localThumbPath, size, shape, large, modifier)

/** What a live station says is on now, or a plain line when it says nothing, with LIVE; for the player's panels. */
@Composable
fun OnAirLine(text: String?, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.TextUnit = 14.sp) = Row(modifier, Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
    Text("LIVE", color = Palette.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
    Text(text?.let { "Now: $it" } ?: "Live radio", color = Palette.ink2, fontSize = size, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Home's way into the Radio page. While a station plays it says which, with LIVE, and the wave on its top edge swells and moves. */
@Composable
fun RadioCard(services: Services, modifier: Modifier = Modifier, glass: Boolean = false, onOpen: () -> Unit) {
    val state by services.player.state.collectAsState()
    val station = state.current?.takeIf { it.isRadio }
    val live = station != null && state.isPlaying
    val swell by animateFloatAsState(if (live) 1f else 0f, tween(900))
    val drift = remember { Animatable(0f) }
    LaunchedEffect(live) { if (live) while (true) { drift.snapTo(0f); drift.animateTo(1f, tween(4000, easing = LinearEasing)) } }
    val accent = Palette.accent
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier.clip(shape)
            .then(if (glass) Modifier.frosted(0.5f).border(1.dp, Color.White.copy(alpha = 0.1f), shape) else Modifier.background(Palette.ink.copy(alpha = 0.05f)))
            .clickable(onClick = onOpen),
    ) {
        // a hand-drawn line along the top: nearly flat at rest, a wave that runs while a station plays
        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            val amp = (2f + 8f * swell).dp.toPx()
            val path = Path()
            val steps = 64
            for (i in 0..steps) {
                val x = size.width * i / steps
                val y = size.height / 2 + amp * sin(2 * PI * (1.6 * i / steps - drift.value)).toFloat() * (.35f + .65f * sin(PI * i / steps).toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, accent.copy(alpha = .35f + .5f * swell), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        Row(Modifier.padding(start = 18.dp, end = 22.dp, bottom = 14.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(accent.copy(alpha = .16f)), Alignment.Center) { Icon(Icons.Radio, null, Modifier.size(24.dp), tint = accent) }
            Column(Modifier.weight(1f)) {
                Text("Radio", fontFamily = Heading, fontSize = 20.sp, color = Palette.ink)
                Text(
                    if (station == null) buildAnnotatedString { append("Live stations and nonstop music") } else buildAnnotatedString {
                        append(state.onAir ?: station.title); append("  ")
                        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = .8.sp)) { append("LIVE") }
                    },
                    color = Palette.ink2, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text("Open ›", color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Live radio: nonstop mixes, the stations you starred, then a searchable list of the most listened ones. */
@Composable
fun RadioPage(services: Services, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val lib = services.library
    val mine by lib.radioFavourites.stations.collectAsState()
    val now by services.player.state.collectAsState()
    val nowId = now.current?.id
    val forYou = lib.forYou?.shown?.collectAsState()?.value
    var query by remember { mutableStateOf("") }
    // starts as this computer's own region, when the list has it
    var country by remember { mutableStateOf(java.util.Locale.getDefault().country.takeIf { c -> RADIO_COUNTRIES.any { it.second == c } }) }
    var genre by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    val result by produceState<Result<List<RadioStation>>?>(null, query, country, genre, tick) {
        value = null
        if (query.isNotEmpty()) delay(350)
        value = try { Result.success(lib.radio.search(query, country.orEmpty(), genre.orEmpty())) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }
    var starting by remember { mutableStateOf<String?>(null) }
    var failedMix by remember { mutableStateOf<String?>(null) }
    // songs from the first lists a mood search finds, shuffled; the player adds similar songs when they run low, so it goes on
    fun startMix(m: Mood) {
        if (starting != null) return
        starting = m.name; failedMix = null
        services.scope.launch {
            try {
                val songs = moodLists(services.source, m, 2).flatMap { l ->
                    try { services.source.page(l).songs.take(40) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                }.distinctBy { it.id }
                if (songs.isEmpty()) failedMix = m.name else services.player.shuffleList(songs, "Nonstop · ${m.name}")
            } catch (e: CancellationException) { throw e } catch (_: Exception) { failedMix = m.name }
            finally { starting = null }
        }
    }

    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp + LocalBottomInset.current)) {
        item {
            Text("‹ Home", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(vertical = 6.dp, horizontal = 4.dp))
            Kicker("Live stations and nonstop music", Modifier.padding(top = 14.dp))
            Text("Radio", fontFamily = Heading, fontSize = 44.sp, letterSpacing = (-1).sp, color = Palette.ink, modifier = Modifier.padding(top = 6.dp))
        }
        item { HomeSection("Nonstop music", null) }
        item { Text("Endless songs, no ads", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp)) }
        item {
            SlideRow(arrowTop = 30.dp) {
                if (!forYou.isNullOrEmpty()) item {
                    Box(
                        Modifier.width(190.dp).height(104.dp).clip(RoundedCornerShape(16.dp)).background(Palette.accent).drawBehind { moodArt { you() } }
                            .clickable { services.player.playList(forYou, 0, "Made for you") }.padding(16.dp),
                        Alignment.BottomStart,
                    ) { Text("Made for you", fontFamily = Heading, fontSize = 20.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                items(NONSTOP) { m -> MoodTile(m, { startMix(m) }, Modifier.width(190.dp)) }
            }
        }
        starting?.let { item { Text("Finding songs for $it…", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) } }
        failedMix?.let { item { Text("Couldn't start $it. Check your connection and try again.", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) } }
        if (mine.isNotEmpty()) {
            item { HomeSection("Your stations", null) }
            item {
                SlideRow(arrowTop = 56.dp) {
                    items(mine, key = { it.uuid }) { s -> StationCard(s, nowId == "radio:${s.uuid}") { playStation(services, s) } }
                }
            }
        }
        item { HomeSection("Live radio", null) }
        item {
            Row(Modifier.padding(top = 8.dp).fillMaxWidth().height(44.dp).clip(CircleShape).glassFill().padding(horizontal = 16.dp), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
                Icon(Icons.Search, null, Modifier.size(20.dp), tint = Palette.ink2)
                val field = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 15.sp, color = Palette.ink)
                Box(Modifier.weight(1f), Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Search stations", style = field, color = Palette.ink.copy(alpha = 0.4f))
                    BasicTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = field, cursorBrush = SolidColor(Palette.accent))
                }
            }
        }
        item {
            LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { CountryChip(country) { country = it } }
                items(RADIO_GENRES) { g -> FilterChip(g.replaceFirstChar { it.uppercase() }, genre == g) { genre = if (genre == g) null else g } }
            }
        }
        item { Text(if (query.isBlank() && genre == null) "Popular" else "Results", fontFamily = Heading, fontSize = 17.sp, color = Palette.ink, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)) }
        val r = result
        when {
            r == null -> item { Text("Finding stations…", color = Palette.ink2, modifier = Modifier.padding(top = 8.dp)) }
            r.isFailure -> item {
                Row(Modifier.padding(top = 8.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
                    Text("Couldn't load the station list. Check your connection.", color = Palette.ink2)
                    Pill("Try again") { tick++ }
                }
            }
            r.getOrThrow().isEmpty() -> item { Text("No stations found. Try another word, genre or country.", color = Palette.ink2, modifier = Modifier.padding(top = 8.dp)) }
            else -> items(r.getOrThrow(), key = { it.uuid }) { s ->
                StationRow(s, nowId == "radio:${s.uuid}", mine.any { it.uuid == s.uuid }, { playStation(services, s) }, { lib.radioFavourites.toggle(s) })
            }
        }
    }
}

@Composable
private fun StationCard(s: RadioStation, playing: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hot by src.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hot) 1.04f else 1f, tween(200))
    Column(Modifier.width(140.dp).hoverable(src).clickable(src, null, onClick = onClick), Arrangement.spacedBy(8.dp)) {
        StationCover(s.name, s.logo, 140.dp, RoundedCornerShape(12.dp), Modifier.graphicsLayer { scaleX = lift; scaleY = lift })
        Text(s.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (playing) Palette.accent else Palette.ink)
    }
}

@Composable
private fun StationRow(s: RadioStation, playing: Boolean, starred: Boolean, onPlay: () -> Unit, onStar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPlay).padding(horizontal = 10.dp),
        Arrangement.spacedBy(14.dp), Alignment.CenterVertically,
    ) {
        StationCover(s.name, s.logo, 46.dp, RoundedCornerShape(8.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, fontFamily = Display, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (playing) Palette.accent else Palette.ink)
            Text(listOfNotNull(s.country.ifBlank { null }, s.tags.split(',').firstOrNull { it.isNotBlank() }).joinToString(" · "), color = Palette.ink2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StarButton(starred, s.name, onStar)
    }
}

/** Adds a station to Your stations, or takes it out. */
@Composable
fun StarButton(starred: Boolean, name: String, onClick: () -> Unit) = Tip(if (starred) "Remove from Your stations" else "Add to Your stations") {
    Icon(
        if (starred) Icons.Star else Icons.StarOutline, if (starred) "Remove $name from Your stations" else "Add $name to Your stations",
        Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onClick).padding(8.dp), tint = if (starred) Palette.accent else Palette.ink2,
    )
}

/** A filter button: a soft accent tint when chosen. */
@Composable
private fun FilterChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(CircleShape).background(if (on) Palette.accent.copy(alpha = .18f) else Palette.ink.copy(alpha = .06f)).clickable(onClick = onClick).padding(horizontal = 16.dp),
        Alignment.Center,
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) Palette.accent else Palette.ink, maxLines = 1) }
}

@Composable
private fun CountryChip(code: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = RADIO_COUNTRIES.firstOrNull { it.second == code }?.first ?: "All countries"
    Box {
        FilterChip(name, code != null) { open = true }
        DropdownMenu(open, { open = false }, containerColor = Palette.surface) {
            DropdownMenuItem({ Text("All countries", color = Palette.ink) }, onClick = { onPick(null); open = false })
            RADIO_COUNTRIES.forEach { (n, c) -> DropdownMenuItem({ Text(n, color = Palette.ink) }, onClick = { onPick(c); open = false }) }
        }
    }
}
