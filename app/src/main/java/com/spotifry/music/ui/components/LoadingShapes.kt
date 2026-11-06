package com.spotifry.music.ui.components

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val SHOW_AFTER_MS = 150L
private const val PULSE_MS = 1_400

/**
 * Grey stand-ins laid out like what's coming. They take their space at once but only show after a moment, so a quick load
 * doesn't flash and nothing moves when the real thing arrives. A screen reader hears one "Loading" for the group.
 */
@Composable
fun LoadingShapes(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(SHOW_AFTER_MS); show = true }
    val context = LocalContext.current
    val still = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val pulse = if (still) null else rememberInfiniteTransition("loading").animateFloat(1f, .6f, infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse), "pulse")
    Column(
        modifier.graphicsLayer { alpha = if (show) pulse?.value ?: 1f else 0f }
            .then(if (show) Modifier.semantics { contentDescription = "Loading" } else Modifier),
        content = content,
    )
}

/** One grey shape. */
@Composable
fun LoadingBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp)) =
    Box(modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = .1f), shape))

/** A song row: the same height as the real one, with [width] by [height] where its cover or track number goes. */
@Composable
fun LoadingRow(modifier: Modifier = Modifier, width: Dp = 52.dp, height: Dp = 52.dp, round: Boolean = false) {
    Row(modifier.testTag("loading_row").fillMaxWidth().defaultMinSize(minHeight = 70.dp).padding(vertical = 9.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
        LoadingBox(Modifier.size(width, height), if (round) CircleShape else RoundedCornerShape(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LoadingBox(Modifier.size(150.dp, 14.dp), RoundedCornerShape(3.dp))
            LoadingBox(Modifier.size(90.dp, 11.dp), RoundedCornerShape(3.dp))
        }
    }
}
