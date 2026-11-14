package com.spotifry.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The three places in the top row. Search, Playlists and Settings are reached from the search bar, Saved and the gear. */
enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) { Home("Home", Icons.Home), NowPlaying("Now playing", Icons.Wave), Saved("Saved", Icons.Saved) }

/** The row on top: the logo, the three tabs, the search bar (Ctrl+K) and the settings gear. */
@Composable
fun TopBar(
    tab: Tab?, onTab: (Tab) -> Unit, onSettings: () -> Unit, settingsOn: Boolean,
    compact: Boolean, update: DesktopRelease? = null, onUpdate: () -> Unit = {}, modifier: Modifier = Modifier, jumpBar: @Composable (tight: Boolean) -> Unit,
) = androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
    // the row's own room, not the window's: beside the side panel a 1280 window leaves about 900, where the full
    // spacing pushed the gear off the edge; there everything sits closer and the search bar is narrower
    val tight = maxWidth < 1000.dp
    val close = compact || tight
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = if (close) 28.dp else 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (tight) 12.dp else if (compact) 18.dp else 28.dp)) {
        Text(buildLogo(), fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 21.sp)
        Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(if (close) 2.dp else 8.dp)) { Tab.entries.forEach { TabPill(it, it == tab) { onTab(it) } } }
        Spacer(Modifier.weight(1f))
        jumpBar(tight)
        // a newer version out: a small accent pill by the gear, which opens its download page
        if (update != null) Tip("Spotifry ${update.version} is out. Opens the download page.") {
            Row(
                Modifier.height(38.dp).clip(CircleShape).background(Palette.accent).clickable(onClick = onUpdate).padding(horizontal = if (tight) 11.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Download, "Update", Modifier.size(16.dp), tint = Color.White)
                if (!tight) Text("Update", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        Box(Modifier.size(38.dp).clip(CircleShape).glassFill().clickable(onClick = onSettings), contentAlignment = Alignment.Center) {
            Icon(Icons.Settings, "Settings", Modifier.size(22.dp), tint = if (settingsOn) Palette.accent else Palette.ink.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun buildLogo() = androidx.compose.ui.text.buildAnnotatedString {
    append("Spotifry")
    pushStyle(androidx.compose.ui.text.SpanStyle(color = Palette.accent)); append("."); pop()
}

/** A page in the top row: its icon and name, sitting in a soft accent pill when it is the page shown. */
@Composable
private fun TabPill(tab: Tab, on: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hot by src.collectIsHoveredAsState()
    val ink by animateColorAsState(if (on) Palette.accent else if (hot) Palette.ink else Palette.ink.copy(alpha = 0.7f), tween(180))
    val fill by animateColorAsState(if (on) Palette.accent.copy(alpha = 0.16f) else if (hot) Palette.ink.copy(alpha = 0.06f) else Color.Transparent, tween(180))
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(fill).hoverable(src).clickable(src, null, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(tab.icon, null, Modifier.size(18.dp), tint = ink)
        Text(tab.label.uppercase(), fontSize = 13.sp, letterSpacing = 1.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, color = ink, maxLines = 1)
    }
}

/** A text tab with a line under it: the line and the colour fade in when the pointer is over it, no box. */
@Composable
fun HoverTab(text: String, on: Boolean, size: androidx.compose.ui.unit.TextUnit, spacing: androidx.compose.ui.unit.TextUnit, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hot by src.collectIsHoveredAsState()
    val ink by animateColorAsState(if (on || hot) Palette.ink else Palette.ink.copy(alpha = 0.62f), tween(180))
    val line by animateColorAsState(if (on) Palette.accent else if (hot) Palette.ink.copy(alpha = 0.35f) else Color.Transparent, tween(180))
    Column(Modifier.width(IntrinsicSize.Max).hoverable(src).clickable(src, null, onClick = onClick).padding(vertical = 6.dp)) {
        Text(text, fontSize = size, letterSpacing = spacing, color = ink, maxLines = 1)
        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(1.5.dp).background(line))
    }
}

/** Small uppercase line above a headline. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier) = Text(text.uppercase(), fontSize = 12.sp, letterSpacing = 2.sp, color = Palette.ink.copy(alpha = 0.62f), modifier = modifier)

/** Songs / Playlists: the two lists under the Saved tab. */
@Composable
fun SavedFilters(on: Route, go: (Route) -> Unit) = Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    listOf("Songs" to Route.Saved, "Playlists" to Route.Playlists).forEach { (label, r) -> HoverTab(label.uppercase(), on == r, 13.sp, 1.sp) { go(r) } }
}

/** How much room the bottom player bar takes: lists end this far above the window's bottom so their last row can be scrolled clear of it. */
val LocalBottomInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

/**
 * The song's sound as plain text. A lossless one ("Lossless", "Hi-Res Lossless") gets the bars mark and a glint of the accent colour
 * that slides across it, like Apple Music's label; a lossy one has a headphones mark and stays still.
 */
@Composable
fun QualityPill(text: String, modifier: Modifier = Modifier) {
    val lossless = text.contains("Lossless")
    val ink = Palette.ink.copy(alpha = 0.9f)
    // a quiet grey that a brighter band sweeps over: white on the dark theme, black on the light one
    val base = Palette.ink.copy(alpha = 0.6f)
    val glint = Palette.ink
    val shine = rememberInfiniteTransition(label = "shine").animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "x")
    // the glint is a gradient painted over the words and the mark only where they have pixels (SrcIn); read while drawing, so it never recomposes
    val shiny = if (!lossless) Modifier else Modifier.graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val band = size.width * 0.7f
        val x = -band + (size.width + 2 * band) * shine.value
        drawRect(Brush.linearGradient(listOf(base, glint, base), start = Offset(x, 0f), end = Offset(x + band, 0f)), blendMode = BlendMode.SrcIn)
    }
    Row(modifier.then(shiny), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(if (lossless) Icons.Lossless else Icons.Headphones, null, if (lossless) Modifier.size(width = 32.dp, height = 20.dp) else Modifier.size(18.dp), tint = ink)
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Medium, fontStyle = if (lossless) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal, color = ink, maxLines = 1)
    }
}

/** Shows [text] above the pointer after a short hover: the helper for icon-only buttons. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Tip(text: String, content: @Composable () -> Unit) = androidx.compose.foundation.TooltipArea(
    tooltip = { Text(text, fontSize = 12.sp, color = Palette.ink, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.surface).border(1.dp, Palette.ink.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 5.dp)) },
    delayMillis = 450, tooltipPlacement = androidx.compose.foundation.TooltipPlacement.CursorPoint(offset = androidx.compose.ui.unit.DpOffset(0.dp, (-44).dp)), content = content,
)
