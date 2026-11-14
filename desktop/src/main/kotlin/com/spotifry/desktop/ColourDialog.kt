package com.spotifry.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val W = 260.dp
private val H = 160.dp

/** Pick any accent: a square for how strong and how bright, a bar for the hue. The shades the app uses are made readable afterwards. */
@Composable
fun ColourDialog(start: Color, onDismiss: () -> Unit, onPick: (Color) -> Unit) {
    val (h0, s0, v0) = remember { hsv(start) }
    var h by remember { mutableStateOf(h0) }
    var s by remember { mutableStateOf(s0) }
    var v by remember { mutableStateOf(v0) }
    val picked = Color.hsv(h, s, v)
    val shade = readableAccent(picked).let { if (Palette.dark) it.first else it.second }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.surface,
        title = { Text("Accent colour", fontFamily = Heading, fontSize = 22.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // saturation across, brightness down
                Box(
                    Modifier.size(W, H).clip(RoundedCornerShape(12.dp))
                        .background(Brush.horizontalGradient(listOf(Color.White, Color.hsv(h, 1f, 1f))))
                        .pointerInput(Unit) {
                            fun at(o: Offset) { s = (o.x / size.width).coerceIn(0f, 1f); v = 1f - (o.y / size.height).coerceIn(0f, 1f) }
                            detectDragGestures(onDragStart = ::at) { c, _ -> at(c.position) }
                        }
                        .pointerInput(Unit) { detectTapGestures { o -> s = (o.x / size.width).coerceIn(0f, 1f); v = 1f - (o.y / size.height).coerceIn(0f, 1f) } },
                ) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
                    Knob(Modifier.offset { IntOffset((s * W.toPx()).roundToInt() - 9.dp.roundToPx(), ((1f - v) * H.toPx()).roundToInt() - 9.dp.roundToPx()) }, picked)
                }
                // hue
                Box(
                    Modifier.size(W, 22.dp).clip(RoundedCornerShape(11.dp))
                        .background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) }))
                        .pointerInput(Unit) {
                            fun at(o: Offset) { h = (o.x / size.width).coerceIn(0f, 1f) * 360f }
                            detectDragGestures(onDragStart = ::at) { c, _ -> at(c.position) }
                        }
                        .pointerInput(Unit) { detectTapGestures { o -> h = (o.x / size.width).coerceIn(0f, 1f) * 360f } },
                ) { Knob(Modifier.offset { IntOffset((h / 360f * W.toPx()).roundToInt() - 9.dp.roundToPx(), 2.dp.roundToPx()) }, Color.hsv(h, 1f, 1f)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(shade))
                    Text("As the app shows it, easy to read on this theme.", color = Palette.ink2, fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton({ onPick(picked) }) { Text("Use this colour", color = Palette.accent) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Palette.ink2) } },
    )
}

@Composable
private fun Knob(modifier: Modifier, fill: Color) = Box(modifier.size(18.dp).clip(CircleShape).background(fill).border(2.dp, Color.White, CircleShape))
