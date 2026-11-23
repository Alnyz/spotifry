package com.spotifry.music.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.data.source.RadioStation
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.Cover
import com.spotifry.music.ui.components.ErrorRow
import com.spotifry.music.ui.components.LoadingRow
import com.spotifry.music.ui.components.LoadingShapes
import com.spotifry.music.ui.components.ScreenHeader
import com.spotifry.music.ui.components.SectionTitle
import com.spotifry.music.ui.home.ALL_DAY
import com.spotifry.music.ui.home.MOODS
import com.spotifry.music.ui.home.MoodTile
import com.spotifry.music.ui.home.NIGHT_DRIVE
import com.spotifry.music.ui.home.moodArt
import com.spotifry.music.ui.home.you

/** Live radio: the stations you starred, then a searchable list of the most listened ones. [nowId] is the song id playing now. */
@Composable
fun RadioScreen(vm: RadioViewModel, nowId: String?, onBack: () -> Unit, bottomInset: Dp) {
    val ui by vm.stations.collectAsStateWithLifecycle()
    val mine by vm.favourites.stations.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val country by vm.country.collectAsStateWithLifecycle()
    val genre by vm.genre.collectAsStateWithLifecycle()
    val madeForYou by vm.hasMadeForYou.collectAsStateWithLifecycle()
    val starting by vm.starting.collectAsStateWithLifecycle()
    val failedMix by vm.startFailed.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = bottomInset + 16.dp)) {
        item { ScreenHeader("Radio", subtitle = "Live stations and nonstop music", onBack = onBack) }
        item { SectionTitle("Nonstop music") }
        item { Text("Endless songs, no ads", Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp).offset(y = (-8).dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant) }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (madeForYou) item { MadeForYouCard(vm::startMadeForYou) }
                items(NONSTOP) { m -> MoodTile(m, vm::startMix, Modifier.width(150.dp).height(104.dp)) }
            }
        }
        starting?.let { item { Text("Finding songs for $it…", Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant) } }
        failedMix?.let { item { Text("Couldn't start $it. Check your connection and try again.", Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.error) } }
        if (mine.isNotEmpty()) {
            item { SectionTitle("Your stations") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(mine, key = { it.uuid }) { s -> StationCard(s, nowId == "radio:${s.uuid}") { vm.play(s) } }
                }
            }
        }
        item { SectionTitle("Live radio") }
        item {
            TextField(
                query, { vm.query.value = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp), singleLine = true, shape = CircleShape,
                placeholder = { Text("Search stations") }, leadingIcon = { Icon(Icons.Search, null) },
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = scheme.surface, unfocusedContainerColor = scheme.surface,
                ),
            )
        }
        item {
            LazyRow(Modifier.padding(top = 12.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { CountryPill(country) { vm.country.value = it } }
                items(RADIO_GENRES) { g -> Pill(g.replaceFirstChar { it.uppercase() }, genre == g) { vm.pickGenre(g) } }
            }
        }
        item { Text(if (query.isBlank() && genre == null) "Popular" else "Results", Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) }
        when (val u = ui) {
            StationsUi.Loading -> item { LoadingShapes(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp)) { repeat(6) { LoadingRow() } } }
            StationsUi.Failed -> item { ErrorRow("The station list", vm::retry, Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) }
            is StationsUi.Ready ->
                if (u.stations.isEmpty()) item {
                    Text("No stations found. Try another word, genre or country.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                } else items(u.stations, key = { it.uuid }) { s ->
                    StationRow(s, nowId == "radio:${s.uuid}", mine.any { it.uuid == s.uuid }, { vm.play(s) }, { vm.favourites.toggle(s) })
                }
        }
    }
}

/** The mixes on the page, in this order; "Made for you" comes first when there is one. */
private val NONSTOP = listOf(ALL_DAY) + MOODS.filter { it.name in setOf("Chill", "Focus", "Workout") } + NIGHT_DRIVE

@Composable
private fun MadeForYouCard(onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.width(150.dp).height(104.dp).clip(RoundedCornerShape(16.dp)).background(scheme.primary).drawBehind { moodArt { you() } }.clickable(role = Role.Button, onClick = onOpen).padding(12.dp),
        Alignment.BottomStart,
    ) { Text("Made for you", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = scheme.onPrimary, maxLines = 2) }
}

@Composable
private fun StationCard(s: RadioStation, playing: Boolean, onClick: () -> Unit) {
    // not clipped as a whole: the rounded corner would cut the first letters of the name under the cover
    Column(Modifier.width(112.dp).clickable(onClickLabel = "Play ${s.name}", onClick = onClick), Arrangement.spacedBy(8.dp)) {
        Cover("radio:${s.uuid}", s.logo, null, 112.dp, radius = 16.dp, label = s.name)
        Text(s.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StationRow(s: RadioStation, playing: Boolean, starred: Boolean, onPlay: () -> Unit, onStar: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClickLabel = "Play ${s.name}", onClick = onPlay).padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
        Cover("radio:${s.uuid}", s.logo, null, 52.dp, radius = 12.dp, label = s.name)
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) scheme.primary else scheme.onSurface)
            Text(listOfNotNull(s.country.ifBlank { null }, s.tags.split(',').firstOrNull { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onStar, Modifier.size(48.dp)) {
            Icon(if (starred) Icons.Star else Icons.StarOutline, if (starred) "Remove ${s.name} from Your stations" else "Add ${s.name} to Your stations", tint = if (starred) scheme.primary else scheme.onSurfaceVariant)
        }
    }
}

/** A flat filter button: a soft accent tint when chosen. */
@Composable
private fun Pill(text: String, on: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.heightIn(min = 44.dp).clip(CircleShape).background(if (on) scheme.primary.copy(alpha = .18f) else scheme.surface)
            .clickable(role = Role.Button, onClick = onClick).semantics { selected = on }.padding(horizontal = 16.dp),
        Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (on) scheme.primary else scheme.onSurface) }
}

@Composable
private fun CountryPill(code: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = RADIO_COUNTRIES.firstOrNull { it.second == code }?.first ?: "All countries"
    Box {
        Pill(name, code != null) { open = true }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("All countries") }, onClick = { onPick(null); open = false })
            RADIO_COUNTRIES.forEach { (n, c) -> DropdownMenuItem(text = { Text(n) }, onClick = { onPick(c); open = false }) }
        }
    }
}
