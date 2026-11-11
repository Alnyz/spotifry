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
    query: String, onQuery: (String) -> Unit, onSearch: () -> Unit, jump: FocusRequester, compact: Boolean, modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().height(64.dp).padding(horizontal = if (compact) 28.dp else 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 28.dp)) {
        Text(buildLogo(), fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 21.sp)
        Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) { Tab.entries.forEach { TabLabel(it.label, it == tab) { onTab(it) } } }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.width(if (compact) 210.dp else 230.dp).height(34.dp).clip(CircleShape).background(Palette.ink.copy(alpha = 0.04f)).border(1.dp, Palette.ink.copy(alpha = 0.14f), CircleShape).padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Jump to anything", color = Palette.ink.copy(alpha = 0.4f), fontSize = 13.sp, maxLines = 1)
                BasicTextField(
                    query, onQuery, singleLine = true, textStyle = TextStyle(color = Palette.ink, fontSize = 13.sp), cursorBrush = SolidColor(Palette.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(jump).onEnter(onSearch)
                        .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onQuery(""); true } else false },
                )
            }
            Text("Ctrl K", fontSize = 11.sp, color = Palette.ink.copy(alpha = 0.62f), modifier = Modifier.border(1.dp, Palette.ink.copy(alpha = 0.14f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
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
private fun TabLabel(text: String, on: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(IntrinsicSize.Max).clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text(text.uppercase(), fontSize = 13.sp, letterSpacing = 1.5.sp, color = if (on) Palette.ink else Palette.ink.copy(alpha = 0.62f), maxLines = 1)
        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(1.5.dp).background(if (on) Palette.accent else androidx.compose.ui.graphics.Color.Transparent))
    }
}

/** Small uppercase line above a headline. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier) = Text(text.uppercase(), fontSize = 12.sp, letterSpacing = 2.sp, color = Palette.ink.copy(alpha = 0.62f), modifier = modifier)

/** Songs / Playlists: the two lists under the Saved tab. */
@Composable
fun SavedFilters(on: Route, go: (Route) -> Unit) = Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    listOf("Songs" to Route.Saved, "Playlists" to Route.Playlists).forEach { (label, r) ->
        val me = on == r
        Column(Modifier.width(IntrinsicSize.Max).clickable { go(r) }) {
            Text(label.uppercase(), fontSize = 13.sp, letterSpacing = 1.sp, color = if (me) Palette.ink else Palette.ink.copy(alpha = 0.62f))
            Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(1.5.dp).background(if (me) Palette.accent else Color.Transparent))
        }
    }
}

/** How much room the bottom player bar takes: lists end this far above the window's bottom so their last row can be scrolled clear of it. */
val LocalBottomInset = androidx.compose.runtime.compositionLocalOf { 0.dp }
