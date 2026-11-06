package com.spotifry.music.ui.firstlaunch

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.data.MIN_ARTISTS
import com.spotifry.music.data.PickedArtist
import com.spotifry.music.data.SpotifyImport
import com.spotifry.music.data.db.SongEntity
import com.spotifry.music.ui.Icons
import com.spotifry.music.ui.components.CoverArt
import com.spotifry.music.ui.components.CoverMosaic
import com.spotifry.music.ui.components.Pill
import com.spotifry.music.ui.home.SearchField
import com.spotifry.music.ui.playlists.ImportState
import com.spotifry.music.ui.playlists.ImportViewModel
import com.spotifry.music.ui.playlists.Running
import com.spotifry.music.ui.playlists.Title

/**
 * The two first-launch screens: pick artists, then paste a Spotify playlist link. The link screen is the existing import
 * ([importVm]), drawn full screen. [onClose] is only for [FirstLaunchViewModel.reopen].
 */
@Composable
fun FirstLaunchScreen(
    vm: FirstLaunchViewModel, importVm: ImportViewModel, covers: Map<Long, List<SongEntity>>, onClose: () -> Unit = {},
) {
    val step by vm.step.collectAsStateWithLifecycle()
    val importing by importVm.state.collectAsStateWithLifecycle()
    val running = importing is ImportState.Opening || importing is ImportState.Running
    val finish = { importVm.close(); vm.finish() }
    val context = LocalContext.current
    // read when something fails, not kept: a connection that comes back is seen on the next try
    val offline = remember(importing) { !isOnline(context) }

    // reopened from Home there is no link screen, and an import running from Playlists is not ours to stop
    LaunchedEffect(step) { if (step == 2) importVm.open() else if (!vm.reopen) importVm.close() }
    // a cancelled import closes the import; the link box comes back
    LaunchedEffect(importing, step) { if (step == 2 && importing == ImportState.Closed) importVm.open() }
    // nothing to go back to on the first screen of a first launch; while the import runs the back key waits for Cancel import
    BackHandler(enabled = step == 2 || vm.reopen) {
        if (running) return@BackHandler
        // a finished import is not thrown away by going back: that ends the first launch
        if (importing is ImportState.Done) finish() else if (step == 2) vm.back() else onClose()
    }
    
    // a Surface, so text takes the theme's ink: outside the Scaffold it would default to black on the dark background
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        if (step == 1) {
            val starters by vm.starters.collectAsStateWithLifecycle()
            val picked by vm.picked.collectAsStateWithLifecycle()
            val query by vm.query.collectAsStateWithLifecycle()
            val search by vm.search.collectAsStateWithLifecycle()
            val off by vm.offline.collectAsStateWithLifecycle()
            ArtistsStep(
                starters, picked, query, { vm.query.value = it }, search, off,
                stepLabel = if (vm.reopen) null else "1 of 2",
                skipLabel = if (vm.reopen) "Cancel" else "Skip", continueLabel = if (vm.reopen) "Done" else "Continue",
                onToggle = vm::toggle, onRetry = vm::retryPhotos,
                onSkip = { if (vm.reopen) onClose() else vm.skipArtists() },
                onContinue = { vm.next(); if (vm.reopen) onClose() },
                minPicks = vm.minPicks,
            )
        } else {
            LinkStep(
                importing, offline, covers, onImport = importVm::start, onSkip = finish, onCancel = importVm::cancel, onDone = finish,
            )
        }
    }
    }
}

private fun isOnline(context: android.content.Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
    return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

@Composable
private fun TopRow(stepLabel: String?, skipLabel: String, skipEnabled: Boolean, onSkip: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(stepLabel.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            skipLabel,
            Modifier.alpha(if (skipEnabled) 1f else .35f).clickable(enabled = skipEnabled, role = Role.Button, onClick = onSkip).heightIn(min = 48.dp).widthIn(min = 48.dp).padding(horizontal = 6.dp).wrapContentHeight(Alignment.CenterVertically),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun Heading(text: String, sub: String? = null) {
    Text(text, Modifier.padding(horizontal = 20.dp).semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
    if (sub != null) Text(sub, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Foot(content: @Composable () -> Unit) = Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp)) { content() }

/** Screen 1. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtistsStep(
    starters: List<Starter>, picked: List<PickedArtist>, query: String, onQuery: (String) -> Unit, search: ArtistSearch, offline: Boolean,
    stepLabel: String?, skipLabel: String, continueLabel: String,
    onToggle: (PickedArtist) -> Unit, onRetry: () -> Unit, onSkip: () -> Unit, onContinue: () -> Unit,
    minPicks: Int = MIN_ARTISTS,
) {
    val scheme = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val on = { a: PickedArtist -> picked.any { it.key == a.key } }
    Column(Modifier.fillMaxSize()) {
        TopRow(stepLabel, skipLabel, true, onSkip)
        // while searching, the title gives way: on a small phone the keyboard leaves little room for results
        if (query.isBlank()) Heading("Pick a few artists", "Home fills with their songs. Stays on this phone.")
        Box(Modifier.padding(horizontal = 20.dp, vertical = 16.dp).alpha(if (offline) .5f else 1f)) {
            SearchField(query, onQuery, { keyboard?.hide() }, hint = "Search for an artist")
            // no connection: the box can't search, and shouldn't pretend to
            if (offline) Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (query.isBlank()) {
                LazyVerticalGrid(
                    GridCells.Fixed(3), Modifier.fillMaxSize().testTag("starters"), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(starters, key = { it.name }) { s -> ArtistTile(s.name, s.photo, on(s.picked())) { onToggle(s.picked()) } }
                    if (offline) item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(vertical = 6.dp), Arrangement.spacedBy(10.dp)) {
                            Note("No connection.", "Photos and search need internet. You can still pick from the list, or skip and do this later.", "Try again", onRetry)
                        }
                    }
                }
            } else {
                SearchResults(search, picked.size, on, onToggle)
            }
        }
        // the keyboard is up: the button comes back when it closes (the search key closes it); the count stays in view above
        if (!WindowInsets.isImeVisible) Foot {
            val n = picked.size
            if (n >= minPicks) Pill(null, if (n == 0) "$continueLabel · none picked" else "$continueLabel · $n picked", scheme.primary, scheme.onPrimary, onContinue, Modifier.fillMaxWidth())
            else Pill(null, if (n == 0) "Pick $MIN_ARTISTS or more" else "Pick ${MIN_ARTISTS - n} more", scheme.surfaceContainerHigh, scheme.onSurfaceVariant, {}, Modifier.fillMaxWidth().semantics { disabled() })
        }
    }
}

@Composable
private fun Note(lead: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.surface).border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        Arrangement.spacedBy(6.dp),
    ) {
        Text(androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = scheme.onSurface)) { append(lead) }
            append(" "); append(text)
        }, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        if (action != null) Text(action, Modifier.clickable(role = Role.Button, onClick = onAction).heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
    }
}

@Composable
private fun ArtistTile(name: String, photo: String?, on: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().selectable(on, role = Role.Checkbox, onClick = onClick),
        Arrangement.spacedBy(7.dp), Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(80.dp), Alignment.Center) {
            Box(Modifier.size(76.dp).then(if (on) Modifier.border(2.dp, scheme.primary, CircleShape).padding(4.dp) else Modifier)) { Avatar(name, photo, Modifier.fillMaxSize()) }
            if (on) Box(Modifier.align(Alignment.BottomEnd).size(26.dp).background(scheme.primary, CircleShape).border(3.dp, scheme.background, CircleShape), Alignment.Center) {
                Icon(Icons.Check, null, Modifier.size(14.dp), tint = scheme.onPrimary)
            }
        }
        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** A round photo; with none (offline, or no page found) the first letters of the name. */
@Composable
private fun Avatar(name: String, photo: String?, modifier: Modifier) {
    Box(modifier, Alignment.Center) {
        CoverArt(name, photo, null, Modifier.fillMaxSize(), CircleShape, large = false)
        if (photo == null) Text(
            name.split(' ').filter { it.firstOrNull()?.isLetter() == true }.take(2).joinToString("") { it.take(1) }.ifEmpty { name.take(1) },
            style = MaterialTheme.typography.titleLarge, color = Color.White,
        )
    }
}

@Composable
private fun SearchResults(search: ArtistSearch, count: Int, on: (PickedArtist) -> Boolean, onToggle: (PickedArtist) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    when (search) {
        ArtistSearch.Idle, ArtistSearch.Loading -> Text("Searching…", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        ArtistSearch.Error -> Text("Couldn't search. Check your connection, then try again.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
        is ArtistSearch.Results -> LazyColumn(Modifier.fillMaxSize().testTag("artist results")) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), Arrangement.SpaceBetween) {
                    Text("Artists on YouTube Music", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    Text("$count picked", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                }
            }
            if (search.list.isEmpty()) item { Text("No artists found.", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant) }
            items(search.list.size) { i ->
                val l = search.list[i]
                val a = PickedArtist(l.name, l.url, l.thumbnailUrl)
                val picked = on(a)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(picked, role = Role.Checkbox) { onToggle(a) }.padding(horizontal = 20.dp, vertical = 6.dp),
                    Arrangement.spacedBy(14.dp), Alignment.CenterVertically,
                ) {
                    Avatar(l.name, l.thumbnailUrl, Modifier.size(52.dp))
                    Column(Modifier.weight(1f)) {
                        Text(l.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Artist", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    Box(
                        Modifier.size(28.dp).then(if (picked) Modifier.background(scheme.primary, CircleShape) else Modifier.border(1.5.dp, scheme.outline, CircleShape)),
                        Alignment.Center,
                    ) { if (picked) Icon(Icons.Check, "Picked", Modifier.size(15.dp), tint = scheme.onPrimary) }
                }
            }
        }
    }
}

/** Screen 2: the Spotify link, then the existing import's own progress and result. */
@Composable
internal fun LinkStep(
    state: ImportState, offline: Boolean, covers: Map<Long, List<SongEntity>>,
    onImport: (String) -> Unit, onSkip: () -> Unit, onCancel: () -> Unit, onDone: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val clip = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // a Spotify link already copied is filled in, so the usual case is one tap
    val copied = remember { clip.getText()?.text?.takeIf { SpotifyImport.playlistId(it) != null }.orEmpty() }
    var link by rememberSaveable { mutableStateOf(copied) }
    val running = state is ImportState.Opening || state is ImportState.Running
    Column(Modifier.fillMaxSize()) {
        TopRow(if (state is ImportState.Done) null else "2 of 2", if (state is ImportState.Done) "" else "Skip", skipEnabled = !running && state !is ImportState.Done, onSkip)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            when (state) {
                is ImportState.Opening -> {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Title("Opening the playlist…", null)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 20.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
                is ImportState.Running -> Column(Modifier.padding(horizontal = 20.dp)) { Running(state) }
                is ImportState.Done -> {
                    Heading("Done")
                    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
                        CoverMosaic(covers[state.playlistId].orEmpty(), Modifier.size(64.dp))
                        Column { Title(state.name, "${state.added} of ${state.total} songs added") }
                    }
                    if (state.missing.isNotEmpty()) Text("${state.missing.size} songs were not found on YouTube Music.", Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                else -> {
                    val error = (state as? ImportState.Input)?.error
                    val failed = (state as? ImportState.Failed)?.message
                    // an error belongs to the link that caused it; typing clears it
                    var shownFor by remember(state) { mutableStateOf(link) }
                    val err = error?.takeIf { link == shownFor }
                    Heading("Bring a playlist", "Paste a link from Spotify. Songs are found on YouTube Music. Public playlists only, first 100 songs.")
                    OutlinedTextField(
                        link, { link = it }, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp), singleLine = true,
                        placeholder = { Text("open.spotify.com/playlist/…") }, leadingIcon = { Icon(Icons.Link, null) },
                        isError = err != null, shape = RoundedCornerShape(16.dp),
                        supportingText = { Text(err ?: if (link.isNotEmpty() && link == copied) "Link found on the clipboard, filled in for you." else "Public playlists only, up to 100 songs.") },
                    )
                    if (failed != null && link == shownFor) {
                        Note2(failed, if (offline) "No connection. Reading the link needs internet. Nothing was added." else null)
                    } else Column(
                        Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.surface).padding(16.dp),
                    ) {
                        Text("In Spotify", style = MaterialTheme.typography.titleSmall)
                        listOf("Open the playlist", "Share, then Copy link", "Come back here").forEachIndexed { i, t ->
                            Text("${i + 1}. $t", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Foot {
            when (state) {
                is ImportState.Opening, is ImportState.Running -> Pill(null, "Cancel import", scheme.surfaceContainerHigh, scheme.error, onCancel, Modifier.fillMaxWidth())
                is ImportState.Done -> Pill(null, "Go to Home", scheme.primary, scheme.onPrimary, onDone, Modifier.fillMaxWidth())
                is ImportState.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(null, "Try again", scheme.primary, scheme.onPrimary, { onImport(link) }, Modifier.weight(1f))
                    Pill(null, "Skip", scheme.surfaceContainerHigh, scheme.onSurface, onSkip, Modifier.weight(1f))
                }
                else -> {
                    val ready = link.isNotBlank()
                    if (ready) Pill(null, "Import", scheme.primary, scheme.onPrimary, { keyboard?.hide(); onImport(link) }, Modifier.fillMaxWidth())
                    else Pill(null, "Import", scheme.surfaceContainerHigh, scheme.onSurfaceVariant, {}, Modifier.fillMaxWidth().semantics { disabled() })
                }
            }
        }
    }
}

@Composable
private fun Note2(message: String, extra: String?) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.surface).padding(16.dp),
        Arrangement.spacedBy(6.dp),
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium)
        if (extra != null) Text(extra, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}
