package com.spotifry.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.MIN_ARTISTS
import com.spotifry.music.data.PickedArtist
import com.spotifry.music.data.STARTER_ARTISTS
import com.spotifry.music.data.lookupArtist
import com.spotifry.music.data.source.PageLink
import com.spotifry.music.data.source.SearchType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Pick the artists Home is made from: the starter list (pages and pictures looked up three at a time) and a search for
 * anyone else. [firstLaunch] asks for [MIN_ARTISTS] and has no Back; from Home any number goes, even none.
 */
@Composable
fun ArtistPicker(services: Services, firstLaunch: Boolean, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val settings = services.library.settings
    var picked by remember { mutableStateOf(if (firstLaunch) emptyList() else settings.artists.value) }
    var starters by remember { mutableStateOf(STARTER_ARTISTS.map { PickedArtist(it, "", null) }) }
    var query by remember { mutableStateOf("") }
    // null: nothing typed; empty with `searching`: asking
    var found by remember { mutableStateOf<List<PageLink>?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val gate = Semaphore(3)
        starters.map { s -> async(Dispatchers.IO) {
            gate.withPermit {
                val link = runCatching { services.source.lookupArtist(s.name) }.getOrNull() ?: return@withPermit
                starters = starters.map { if (it.key == s.key) PickedArtist(s.name, link.url, link.thumbnailUrl) else it }
            }
        } }.awaitAll()
    }
    LaunchedEffect(query) {
        failed = false
        if (query.none { it.isLetterOrDigit() }) { found = null; return@LaunchedEffect }
        delay(400)
        runCatching { services.source.searchPages(query.trim(), SearchType.ARTISTS) }.onSuccess { found = it.take(12) }.onFailure { failed = true }
    }
    fun toggle(a: PickedArtist) { picked = if (picked.any { it.key == a.key }) picked.filterNot { it.key == a.key } else picked + a }
    fun done() {
        // Home may have found an artist's page while this was open: keep that over the copy picked earlier
        val now = settings.artists.value.associateBy { it.key }
        settings.setArtists(picked.map { now[it.key] ?: it }); onDone()
    }
    val enough = picked.size >= if (firstLaunch) MIN_ARTISTS else 0

    LazyColumn(modifier.widthIn(max = 900.dp)) {
        if (!firstLaunch) item {
            Text("‹ Home", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onDone() }.padding(vertical = 6.dp, horizontal = 4.dp))
        }
        item {
            PageHeader(if (firstLaunch) "Pick your artists" else "Your artists", if (firstLaunch) "Pick at least $MIN_ARTISTS. Home is made from them, and you can change them later." else "Home shows these, and a few songs from each.") {
                Pill(if (firstLaunch) "Continue" else "Done", primary = true, accent = false) { if (enough) done() }
            }
        }
        item {
            OutlinedTextField(
                query, { query = it }, singleLine = true, label = { Text("Search for an artist") }, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(top = 22.dp).fillMaxWidth(),
            )
            if (!firstLaunch || picked.isNotEmpty()) Text("${picked.size} picked${if (firstLaunch && !enough) " · ${MIN_ARTISTS - picked.size} more to go" else ""}", color = Palette.ink2, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
        item {
            val list = found
            if (failed) Text("Couldn't search. Check the connection.", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
            else if (list != null) {
                if (list.isEmpty()) Text("No artist found", color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    list.forEach { l -> val a = PickedArtist(l.name, l.url, l.thumbnailUrl); PickCircle(a, picked.any { it.key == a.key }) { toggle(a) } }
                }
            }
        }
        item {
            Kicker("Popular", Modifier.padding(top = 28.dp, bottom = 14.dp))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                starters.forEach { a -> PickCircle(a, picked.any { it.key == a.key }) { toggle(a) } }
            }
            // picked from a search or an earlier time and not in the list above
            val extra = picked.filter { p -> starters.none { it.key == p.key } && found?.any { it.name.trim().lowercase() == p.key } != true }
            if (extra.isNotEmpty()) {
                Kicker("Also picked", Modifier.padding(top = 28.dp, bottom = 14.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { extra.forEach { a -> PickCircle(a, true) { toggle(a) } } }
            }
            Spacer(Modifier.height(24.dp + LocalBottomInset.current))
        }
    }
}

/** A round artist you can tick: a ring in the accent colour around the picture when picked. */
@Composable
private fun PickCircle(a: PickedArtist, on: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(120.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp), Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
        Box(Modifier.size(108.dp).then(if (on) Modifier.border(3.dp, Palette.accent, CircleShape) else Modifier).padding(if (on) 5.dp else 2.dp), Alignment.Center) {
            Box(Modifier.alpha(if (on) 1f else .8f)) { Cover(a.thumbnailUrl, if (on) 90.dp else 100.dp, CircleShape) }
        }
        Text(a.name, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, color = if (on) Palette.accent else Palette.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
