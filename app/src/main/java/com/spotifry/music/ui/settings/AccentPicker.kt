package com.spotifry.music.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.spotifry.music.ui.theme.AccentChoice
import com.spotifry.music.ui.theme.accentFor
import com.spotifry.music.ui.theme.hsv
import com.spotifry.music.ui.theme.isDark
import com.spotifry.music.ui.theme.onAccent
import com.spotifry.music.ui.theme.readableAccent
import kotlin.math.roundToInt

/**
 * Pick the accent: a square for how strong and how bright, a rainbow for the hue, or one tap on a ready colour.
 * The top shows the result on a greeting and a button while dragging; the app changes on Done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccentPicker(choice: String, color: Int, onDone: (String, Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dark = isDark()
    var picked by remember { mutableStateOf(AccentChoice.of(choice)) }
    val start = remember { hsv(if (picked == AccentChoice.CUSTOM) Color(color) else accentFor(picked, color, context).dark) }
    var h by remember { mutableFloatStateOf(start.first) }
    var s by remember { mutableFloatStateOf(start.second) }
    var v by remember { mutableFloatStateOf(start.third) }
    val raw = Color.hsv(h, s, v)
    val accent = if (picked == AccentChoice.CUSTOM) readableAccent(raw) else accentFor(picked, color, context)
    val shade = accent.forTheme(dark)
    val custom = { picked = AccentChoice.CUSTOM }
    val pick = { c: AccentChoice ->
        picked = c
        hsv(accentFor(c, color, context).dark).let { (a, b, d) -> h = a; s = b; v = d }
    }
    val scheme = MaterialTheme.colorScheme

    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = scheme.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Accent colour", style = MaterialTheme.typography.headlineSmall)
            // preview: what the colour looks like on the app's own text and button
            Row(
                Modifier.padding(vertical = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.background).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildAnnotatedString { append("Good evening"); withStyle(SpanStyle(color = shade)) { append(".") } },
                    Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                )
                Box(Modifier.clip(CircleShape).background(shade).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Play all", color = onAccent(shade), style = MaterialTheme.typography.labelLarge)
                }
            }
            val hueColor = Color.hsv(h, 1f, 1f)
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(16.dp))
                    .drawBehind {
                        drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    }
                    .semantics { contentDescription = "Colour strength and brightness. Or pick a ready colour below." }
                    .pointerInput(Unit) {
                        val set = { x: Float, y: Float -> s = (x / size.width).coerceIn(0f, 1f); v = 1 - (y / size.height).coerceIn(0f, 1f); custom() }
                        detectTapGestures { set(it.x, it.y) }
                    }
                    .pointerInput(Unit) {
                        val set = { x: Float, y: Float -> s = (x / size.width).coerceIn(0f, 1f); v = 1 - (y / size.height).coerceIn(0f, 1f); custom() }
                        detectDragGestures(onDragStart = { set(it.x, it.y) }) { c, _ -> set(c.position.x, c.position.y) }
                    },
            ) { Knob(raw, maxWidth.value * s, maxHeight.value * (1 - v)) }
            BoxWithConstraints(
                Modifier.padding(top = 14.dp).fillMaxWidth().height(28.dp).clip(CircleShape)
                    .background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }))
                    .semantics { contentDescription = "Hue" }
                    .pointerInput(Unit) { detectTapGestures { h = (it.x / size.width).coerceIn(0f, 1f) * 359.9f; custom() } }
                    .pointerInput(Unit) {
                        detectDragGestures(onDragStart = { h = (it.x / size.width).coerceIn(0f, 1f) * 359.9f; custom() }) { c, _ ->
                            h = (c.position.x / size.width).coerceIn(0f, 1f) * 359.9f; custom()
                        }
                    },
            ) { Knob(hueColor, maxWidth.value * h / 360f, maxHeight.value / 2) }
            Row(Modifier.padding(top = 16.dp), Arrangement.spacedBy(10.dp)) {
                listOfNotNull(AccentChoice.PHONE.takeIf { android.os.Build.VERSION.SDK_INT >= 31 }, AccentChoice.EMBER, AccentChoice.BLUE, AccentChoice.GOLDEN, AccentChoice.CHILI).forEach { c ->
                    val on = picked == c
                    Column(
                        Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.RadioButton, onClickLabel = c.label) { pick(c) }.padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val dot = Modifier.size(40.dp).clip(CircleShape)
                        Box(
                            (if (on) Modifier.border(2.dp, scheme.onSurface, CircleShape).padding(4.dp) else Modifier.padding(4.dp)).then(
                                if (c == AccentChoice.PHONE) dot.background(Brush.sweepGradient(listOf(Color(0xFFE8B4A0), Color(0xFF9FB7D6), Color(0xFFB9D39F), Color(0xFFE8B4A0))))
                                else dot.background(c.accent!!.dark),
                            ),
                        )
                        Text(c.label.substringBefore(' '), style = MaterialTheme.typography.labelMedium, color = if (on) scheme.onSurface else scheme.onSurfaceVariant)
                    }
                }
            }
            val note = when {
                picked != AccentChoice.CUSTOM -> if (picked == AccentChoice.PHONE) "Taken from your wallpaper." else "A ready colour, checked for both themes."
                accent.dark != raw && accent.light != raw -> "Adjusted to stay readable on the dark and light themes."
                accent.dark != raw -> "Adjusted to stay readable on the dark theme (made lighter)."
                accent.light != raw -> "Adjusted to stay readable on the light theme (made deeper)."
                else -> "Easy to read on both themes as it is."
            }
            Text(note, Modifier.padding(top = 10.dp).heightIn(min = 32.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Box(
                Modifier.padding(top = 8.dp).fillMaxWidth().height(48.dp).clip(CircleShape).background(shade)
                    .clickable(role = Role.Button) { onDone(picked.name, raw.toArgb()) },
                Alignment.Center,
            ) { Text("Done", color = onAccent(shade), style = MaterialTheme.typography.titleMedium) }
        }
    }
}

/** A round handle at ([xDp], [yDp]) inside its box, filled with the colour under it. */
@Composable
private fun Knob(fill: Color, xDp: Float, yDp: Float) {
    Box(
        Modifier.offset { IntOffset(((xDp - 13) * density).roundToInt(), ((yDp - 13) * density).roundToInt()) }
            .size(26.dp).clip(CircleShape).background(Color.White).padding(3.dp).clip(CircleShape).background(fill),
    )
}
