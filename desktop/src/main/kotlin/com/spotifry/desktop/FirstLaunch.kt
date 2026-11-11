package com.spotifry.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shown once, on an empty library: a welcome with an optional name, then whether to bring a backup back. */
@Composable
fun FirstLaunch(services: Services, flow: BackupFlow, onDone: () -> Unit) {
    val settings = services.library.settings
    var step by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 120.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (step == 0) {
            androidx.compose.foundation.Image(androidx.compose.ui.graphics.vector.rememberVectorPainter(Logo.image), null, Modifier.size(84.dp))
            Text("Welcome to\nSpotifry.", fontFamily = Serif, fontSize = 52.sp, lineHeight = 56.sp, letterSpacing = (-1).sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 26.dp))
            Text("Music from YouTube, with no ads and no skip limit.", color = Palette.ink2, fontSize = 17.sp, modifier = Modifier.padding(top = 18.dp))
            Box(Modifier.padding(top = 30.dp).width(420.dp).height(52.dp).clip(RoundedCornerShape(26.dp)).background(Palette.ink.copy(alpha = 0.06f)).border(1.dp, Palette.ink.copy(alpha = 0.14f), RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
                if (name.isEmpty()) Text("What should we call you?", color = Palette.ink2, fontSize = 16.sp)
                BasicTextField(
                    name, { name = it }, singleLine = true, textStyle = TextStyle(color = Palette.ink, fontSize = 16.sp, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(Palette.accent), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).onEnter { settings.setName(name); step = 1 },
                )
            }
            Text("Name or nickname. You can leave it empty.", color = Palette.ink2, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Box(Modifier.padding(top = 26.dp)) { Pill("Continue", primary = true) { settings.setName(name); step = 1 } }
        } else {
            Text("Do you have a backup\nto bring back?", fontFamily = Serif, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-1).sp, textAlign = TextAlign.Center)
            Text("A backup from Spotifry on your phone works here too: playlists, history, trims and settings come over.", color = Palette.ink2, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp).widthIn(max = 520.dp))
            Row(Modifier.padding(top = 34.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Choice("Restore a backup", "Pick the .zip file you saved from Settings, Save backup.", Icons.Download, primary = true, onClick = flow::choose)
                Choice("Start fresh", "Search for a song and go. You can restore a backup later in Settings.", Icons.Play, primary = false, onClick = { settings.markFirstLaunchSeen(); onDone() })
            }
        }
    }
}

@Composable
private fun Choice(title: String, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, primary: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(330.dp).clip(RoundedCornerShape(22.dp)).background(if (primary) Palette.accent.copy(alpha = 0.12f) else Palette.ink.copy(alpha = 0.05f))
            .border(1.dp, if (primary) Palette.accent.copy(alpha = 0.5f) else Palette.ink.copy(alpha = 0.12f), RoundedCornerShape(22.dp)).clickable(onClick = onClick).padding(26.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Palette.high), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(24.dp), tint = Palette.accent) }
        Text(title, fontFamily = Serif, fontSize = 22.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
        Text(text, color = Palette.ink2, fontSize = 14.sp, lineHeight = 21.sp)
    }
}
