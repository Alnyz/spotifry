package com.spotifry.music.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.UpdateState
import com.spotifry.music.ui.Icons

/** Top of Home when a newer Spotifry is out: what's new, then Update or Later; download progress while it loads. */
@Composable
fun UpdateCard(state: UpdateState, onUpdate: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val size = { b: Long -> Formatter.formatShortFileSize(context, b) }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(scheme.surface).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
        Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(scheme.primary.copy(alpha = .16f)), Alignment.Center) {
            Icon(Icons.Upload, null, Modifier.size(22.dp), tint = scheme.primary)
        }
        Column(Modifier.weight(1f)) {
            val (title, body) = when (state) {
                is UpdateState.Available -> "Spotifry ${state.release.version} is out" to state.release.headline
                is UpdateState.Downloading -> "Downloading ${state.release.version}…" to
                    "${size(state.doneBytes)} of ${size(state.release.sizeBytes)}. You can keep listening."
                is UpdateState.Failed -> "Couldn't download ${state.release.version}" to "Check your connection, then try again."
                is UpdateState.Ready -> "Spotifry ${state.release.version} is ready" to "Tap Install, then confirm on Android's screen."
                UpdateState.None -> return@Row
            }
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            if (body.isNotEmpty()) Text(body, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 3)
            if (state is UpdateState.Downloading) {
                val total = state.release.sizeBytes.coerceAtLeast(1)
                LinearProgressIndicator({ (state.doneBytes.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp))
            } else Row(Modifier.padding(top = 10.dp), Arrangement.spacedBy(8.dp)) {
                Button(onUpdate) { Text(when (state) { is UpdateState.Failed -> "Try again"; is UpdateState.Ready -> "Install"; else -> "Update" }) }
                if (state is UpdateState.Available) TextButton(onLater) { Text("Later", color = scheme.onSurfaceVariant) }
            }
        }
    }
}
