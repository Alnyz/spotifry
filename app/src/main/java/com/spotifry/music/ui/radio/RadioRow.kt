package com.spotifry.music.ui.radio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.ui.Icons

/** Home's way into the Radio page: the lower half of the entrance card. [live] is the station playing now, if one is. */
@Composable
fun RadioRow(onOpen: () -> Unit, modifier: Modifier = Modifier, live: String? = null) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClickLabel = "Open Radio", onClick = onOpen).padding(start = 16.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        Arrangement.spacedBy(8.dp), Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp), Alignment.Center) { Icon(Icons.Radio, null, Modifier.size(26.dp), tint = scheme.primary) }
        Column(Modifier.weight(1f)) {
            Text("Radio", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            Text(
                if (live == null) buildAnnotatedString { append("Live stations and nonstop music") } else buildAnnotatedString {
                    append(live); append("  ")
                    withStyle(SpanStyle(color = scheme.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = .8.sp)) { append("LIVE") }
                },
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Chevron, null, tint = scheme.onSurfaceVariant)
    }
}
