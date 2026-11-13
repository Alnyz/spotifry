package com.spotifry.music.ui.nowplaying

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spotifry.music.player.AudioPathRow

/** What the sound goes through from the file to the speaker; opens from the quality label on Now playing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPathSheet(rows: List<AudioPathRow>, onClose: () -> Unit) {
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding().padding(start = 22.dp, end = 22.dp, bottom = 24.dp)) {
            Text("Audio path", style = MaterialTheme.typography.headlineSmall)
            rows.forEach { r ->
                Text(r.label, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(r.value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
