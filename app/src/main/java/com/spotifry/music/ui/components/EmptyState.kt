package com.spotifry.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** [buttonLabel] is optional: a screen that already has its own primary button (Playlists) passes none. */
@Composable
fun EmptyState(title: String, body: String, buttonLabel: String? = null, onClick: () -> Unit = {}, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp), Arrangement.spacedBy(12.dp), Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (buttonLabel != null) Button(onClick, Modifier.padding(top = 12.dp).defaultMinSize(minHeight = 48.dp), shape = CircleShape) { Text(buttonLabel) }
    }
}
