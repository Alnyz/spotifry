package com.spotifry.music.ui.names

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.data.NameLook
import com.spotifry.music.data.NameShow
import com.spotifry.music.data.NameStyle
import com.spotifry.music.data.Shown
import com.spotifry.music.ui.Icons
import java.io.File

/** An artist's official logo: offered only when a fanart.tv key is set; [file] is null while [looking] or when none was found. */
data class OfficialChoice(val file: File?, val looking: Boolean = false)

/**
 * Pick how a name looks: a live preview over [backdrop], Rename (playlists only: [onRename]), then Text (the 8 styles,
 * each written with the real name) or Logo. [shown] and [shownLogo] are what the page shows now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameStyleSheet(
    name: String, meta: String, look: NameLook, shown: Shown, shownLogo: File?, ownLogo: File?, official: OfficialChoice?, glow: Color,
    backdrop: @Composable BoxScope.() -> Unit,
    onRename: (() -> Unit)?, onStyle: (NameStyle) -> Unit, onPickLogo: () -> Unit, onUseOwn: () -> Unit, onUseOfficial: (() -> Unit)?,
    onRemoveLogo: () -> Unit, onTint: (Boolean) -> Unit, onClose: () -> Unit,
    titleNote: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    var logoTab by remember { mutableStateOf(shown !is Shown.Text) }
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = scheme.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp), Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column {
                    Text("Name style", fontSize = 21.sp, style = MaterialTheme.typography.titleLarge)
                    if (titleNote != null) Text(titleNote, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                TextButton(onClose) { Text("Done") }
            }
            Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(16.dp))) {
                backdrop()
                Text("Preview", Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.Black.copy(alpha = .45f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp), color = Color.White, fontSize = 11.sp)
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp, start = 12.dp, end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    StyledName(name, shown, shownLogo, Color.White, glow, 34.sp, fallback = look.style)
                    NameMeta(meta, Color.White)
                }
            }
            if (onRename != null) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Name", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                }
                OutlinedButton(onRename) { Icon(Icons.Edit, null, Modifier.size(18.dp)); Text("Rename", Modifier.padding(start = 6.dp)) }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!logoTab, { logoTab = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Text") }
                SegmentedButton(logoTab, { logoTab = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Logo") }
            }
            if (!logoTab) StyleGrid(name, (shown as? Shown.Text)?.style, glow, onStyle)
            else LogoTab(look, ownLogo, official, onPickLogo, onUseOwn, onUseOfficial, onRemoveLogo, onTint)
        }
    }
}

@Composable
private fun StyleGrid(name: String, chosen: NameStyle?, glow: Color, onStyle: (NameStyle) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NameStyle.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { s ->
                    val on = s == chosen
                    Column(Modifier.weight(1f).clickable(role = Role.RadioButton, onClickLabel = s.label) { onStyle(s) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF15181C))
                                .then(if (on) Modifier.border(BorderStroke(2.dp, scheme.primary), RoundedCornerShape(12.dp)) else Modifier).padding(8.dp),
                            Alignment.Center,
                        ) { StyledName(name, Shown.Text(s), null, Color.White, glow, 22.sp) }
                        Text(s.label, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge, color = if (on) scheme.primary else scheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogoTab(look: NameLook, ownLogo: File?, official: OfficialChoice?, onPickLogo: () -> Unit, onUseOwn: () -> Unit, onUseOfficial: (() -> Unit)?, onRemoveLogo: () -> Unit, onTint: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (official != null && onUseOfficial != null) {
            Choice(look.show == NameShow.OFFICIAL || (look.show == null && official.file != null), "Official logo", when { official.file != null -> "Found online"; official.looking -> "Looking online…"; else -> "None found online" }, official.file != null, onUseOfficial)
            Choice(look.show == NameShow.OWN, "Your own logo", "Choose a picture from your phone", true) { if (ownLogo != null) onUseOwn() else onPickLogo() }
        } else if (ownLogo != null) {
            Choice(look.show == NameShow.OWN, "Your logo", "Shown in place of the name", true, onUseOwn)
        } else {
            OutlinedButton(onPickLogo, Modifier.fillMaxWidth()) { Icon(Icons.Picture, null, Modifier.size(18.dp)); Text("Choose a picture", Modifier.padding(start = 6.dp)) }
        }
        if (ownLogo != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onPickLogo) { Icon(Icons.Picture, null, Modifier.size(18.dp)); Text("Choose another picture", Modifier.padding(start = 6.dp)) }
            OutlinedButton(onRemoveLogo) { Icon(Icons.Trash, null, Modifier.size(18.dp)); Text("Remove", Modifier.padding(start = 6.dp)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Match the page colour", style = MaterialTheme.typography.titleMedium)
                Text("White in dark mode, dark in light mode. Turn off to keep the logo's own colours.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            Switch(look.tint, onTint)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Info, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
            Text("A PNG or SVG with a see-through background looks best. A picture with a solid background shows as a box.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Choice(on: Boolean, title: String, sub: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(on, null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}
