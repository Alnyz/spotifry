package com.spotifry.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotifry.music.ui.Icons

/** Top of a screen: bold title, optional line under it, optional back row with a trailing slot. */
@Composable
fun ScreenHeader(
    title: String, modifier: Modifier = Modifier, subtitle: String? = null,
    onBack: (() -> Unit)? = null, trailing: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = if (onBack == null) 22.dp else 4.dp)) {
        if (onBack != null) {
            Row(Modifier.fillMaxWidth().offset(x = (-12).dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                IconButton(onBack, Modifier.size(48.dp)) { Icon(Icons.Back, "Back") }
                Row(Modifier.offset(x = 24.dp)) { trailing() }
            }
        }
        Text(title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Bold section name, like "Recently played". */
@Composable
fun SectionTitle(label: String, modifier: Modifier = Modifier) {
    Text(label, modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineSmall)
}
