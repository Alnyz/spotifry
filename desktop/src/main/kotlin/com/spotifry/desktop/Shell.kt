package com.spotifry.desktop

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
enum class Tab(val label: String) { Home("Home"), NowPlaying("Now playing"), Saved("Saved") }

/** The row on top: the logo, the three tabs, the search bar (Ctrl+K) and the settings gear. */
@Composable
fun TopBar(
    tab: Tab?, onTab: (Tab) -> Unit, onSettings: () -> Unit, settingsOn: Boolean,
    compact: Boolean, modifier: Modifier = Modifier, jumpBar: @Composable () -> Unit,
) {
    Row(modifier.fillMaxWidth().height(64.dp).padding(horizontal = if (compact) 28.dp else 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 28.dp)) {
        Text(buildLogo(), fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 21.sp)
        Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) { Tab.entries.forEach { TabLabel(it.label, it == tab) { onTab(it) } } }
        Spacer(Modifier.weight(1f))
        jumpBar()
        Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onSettings), contentAlignment = Alignment.Center) {
            Icon(Icons.Settings, "Settings", Modifier.size(22.dp), tint = if (settingsOn) Palette.accent else Palette.ink.copy(alpha = 0.62f))
        }
    }
}

@Composable
private fun buildLogo() = androidx.compose.ui.text.buildAnnotatedString {
    append("Spotifry")
    pushStyle(androidx.compose.ui.text.SpanStyle(color = Palette.accent)); append("."); pop()
}

@Composable
private fun TabLabel(text: String, on: Boolean, onClick: () -> Unit) = HoverTab(text.uppercase(), on, 13.sp, 1.5.sp, onClick)

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

/** The small outlined pill for the song's sound, like the phone's quality label. */
@Composable
fun QualityPill(text: String, modifier: Modifier = Modifier) =
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink.copy(alpha = 0.75f), maxLines = 1,
        modifier = modifier.clip(CircleShape).border(1.dp, Palette.ink.copy(alpha = 0.28f), CircleShape).padding(horizontal = 10.dp, vertical = 3.dp))

/** Shows [text] above the pointer after a short hover: the helper for icon-only buttons. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Tip(text: String, content: @Composable () -> Unit) = androidx.compose.foundation.TooltipArea(
    tooltip = { Text(text, fontSize = 12.sp, color = Palette.ink, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.surface).border(1.dp, Palette.ink.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 5.dp)) },
    delayMillis = 450, tooltipPlacement = androidx.compose.foundation.TooltipPlacement.CursorPoint(offset = androidx.compose.ui.unit.DpOffset(0.dp, (-44).dp)), content = content,
)
