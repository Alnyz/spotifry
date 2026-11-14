package com.spotifry.music.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.ui.components.ScreenHeader

/** How much of the phone Spotifry uses and what it is made of, with the replay cache limit and the two clear buttons. */
@Composable
fun StorageScreen(vm: SettingsViewModel, onBack: () -> Unit, bottomInset: Dp, modifier: Modifier = Modifier) {
    val storage by vm.storage.collectAsStateWithLifecycle()
    val cacheMb by vm.settings.cacheMb.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
    LaunchedEffect(Unit) { vm.count() }
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.outline)

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 24.dp)) {
        item { ScreenHeader("Storage", onBack = onBack) }
        item {
            val s = storage
            if (s == null) { Text("Counting…", Modifier.padding(20.dp), color = scheme.onSurfaceVariant); return@item }
            val parts = listOf(
                Triple("Saved songs", s.savedBytes, "${s.savedCount} ${if (s.savedCount == 1) "song" else "songs"}"),
                Triple("Played songs kept for replay", s.songCacheBytes, if (cacheMb == 0) "no limit" else "limit ${cacheLabel(cacheMb)}"),
                Triple("Covers", s.coverBytes, "kept for speed"),
                Triple("Database", s.dbBytes, "playlists, history, settings"),
            )
            val total = parts.sumOf { it.second }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(size(total), style = MaterialTheme.typography.headlineMedium, color = scheme.onSurface)
                Text(
                    if (s.phoneTotal > 0) "used by Spotifry · ${"%.1f".format(total * 100.0 / s.phoneTotal)}% of this phone's ${size(s.phoneTotal)}" else "used by Spotifry",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 14.dp).fillMaxWidth().height(14.dp).clip(CircleShape).background(scheme.surfaceContainerHigh)) {
                    // a part too small to see still gets a sliver, so every colour in the list shows in the bar
                    if (total > 0) parts.forEachIndexed { i, p -> if (p.second > 0) Box(Modifier.weight(p.second.toFloat().coerceAtLeast(total * 0.01f)).fillMaxSize().background(colors[i])) }
                }
            }
            Group("What takes the space") {
                parts.forEachIndexed { i, p ->
                    Item(p.first, p.third) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(colors[i]))
                            Text(size(p.second), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                        }
                    }
                }
            }
            Group("Replay storage") {
                Item("Limit", "Most space for played songs. ∞ keeps them until Android needs the space.", below = true) {
                    Segments(CACHE_MB.map(::cacheLabel), CACHE_MB.indexOf(cacheMb).coerceAtLeast(0), Modifier.fillMaxWidth(), fill = true) { vm.settings.setCacheMb(CACHE_MB[it]) }
                }
                Item("Clear played songs kept for replay", size(s.songCacheBytes), onClick = vm::clearSongCache)
                Item("Clear covers kept for speed", size(s.coverBytes), onClick = vm::clearCovers)
            }
        }
    }
}
