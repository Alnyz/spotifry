package com.spotifry.music.ui.nowplaying

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.player.PipelineGroup
import com.spotifry.music.player.PipelineRow

/** Every step the sound goes through from the file to the speaker; opens from the quality label on Now playing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPipelineSheet(groups: List<PipelineGroup>, onClose: () -> Unit, verdict: String? = null) {
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 24.dp)) {
            Text("Audio pipeline", style = MaterialTheme.typography.headlineSmall)
            Text("Every step from the file to your ears, live.", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (verdict != null) Text(verdict, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Rail(groups, Modifier.padding(top = 16.dp))
        }
    }
}

/** The groups on a thin line: a big dot for each group, a small one for each row, all centred on the line. */
@Composable
private fun Rail(groups: List<PipelineGroup>, modifier: Modifier = Modifier) {
    val rail = MaterialTheme.colorScheme.outline.copy(alpha = .55f)
    Column(modifier.drawBehind { drawLine(rail, Offset(6.dp.toPx(), 8.dp.toPx()), Offset(6.dp.toPx(), size.height - 8.dp.toPx()), strokeWidth = 2.dp.toPx()) }) {
        groups.forEachIndexed { n, g ->
            if (n > 0) Spacer(Modifier.height(18.dp))
            Group(g)
        }
    }
}

@Composable
private fun Group(g: PipelineGroup) {
    val dot = if (g.lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier.fillMaxWidth().drawBehind { drawCircle(dot, 6.dp.toPx(), Offset(6.dp.toPx(), size.height / 2)) }.padding(start = 22.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(g.name.uppercase(), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.9.sp)
        Box(Modifier.weight(1f).height(1.dp).background(line))
    }
    g.rows.forEach { PipelineRowItem(it) }
}

@Composable
private fun PipelineRowItem(row: PipelineRow) {
    val rowDot = MaterialTheme.colorScheme.outline
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().drawBehind { drawCircle(rowDot, 3.dp.toPx(), Offset(6.dp.toPx(), 22.dp.toPx())) }.padding(start = 22.dp, top = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(pipelineIcon(row.icon), null, Modifier.padding(top = 1.dp).size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp)
            Text(
                row.detail, Modifier.padding(top = 1.dp), fontSize = 13.5.sp, lineHeight = 18.sp,
                color = if (row.hot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (row.hot) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        if (row.note != null) Box(
            Modifier.size(40.dp).offset(y = (-8).dp).clip(CircleShape).clickable(role = Role.Button) { open = !open }.semantics { contentDescription = "About ${row.title}" },
            contentAlignment = Alignment.Center,
        ) {
            val c = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Box(Modifier.size(24.dp).border(BorderStroke(1.5.dp, if (open) c else MaterialTheme.colorScheme.outline), CircleShape), contentAlignment = Alignment.Center) {
                Text("i", color = c, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (open && row.note != null) Text(
        row.note, Modifier.padding(start = 56.dp, top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 9.dp),
        fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Gathers what the sheet shows from the player's reports, the settings and Android, and asks for Nearby devices when Bluetooth is connected. */
@Composable
fun AudioPipelineHost(c: com.spotifry.music.AppContainer, songId: String, wholeMs: Long, trim: com.spotifry.music.player.Trim?, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val readings by c.audioTap.readings.collectAsStateWithLifecycle()
    val eqOn by c.settings.eqOn.collectAsStateWithLifecycle()
    val eqName by c.settings.eqPreset.collectAsStateWithLifecycle()
    val leveling by c.settings.sameLoudness.collectAsStateWithLifecycle()
    val fade by c.settings.fadeSeconds.collectAsStateWithLifecycle()
    val speed by c.settings.speed.collectAsStateWithLifecycle()
    val pitch by c.settings.pitch.collectAsStateWithLifecycle()
    val silence by c.settings.skipSilence.collectAsStateWithLifecycle()
    val spatial by c.settings.spatialOn.collectAsStateWithLifecycle()
    var codec by remember { mutableStateOf<com.spotifry.music.player.BtCodec?>(null) }
    var asked by remember { mutableStateOf(false) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { asked = true }
    val bluetooth = com.spotifry.music.player.outputInfo(context).kind == com.spotifry.music.player.OutputKind.Bluetooth
    androidx.compose.runtime.LaunchedEffect(bluetooth, asked) {
        if (!bluetooth) return@LaunchedEffect
        if (com.spotifry.music.player.needsBluetoothPermission(context)) { if (!asked) ask.launch(android.Manifest.permission.BLUETOOTH_CONNECT) }
        else codec = com.spotifry.music.player.bluetoothCodec(context)
    }
    val tune by c.settings.tune.collectAsStateWithLifecycle()
    var saved by remember(songId) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(songId) { saved = c.db.songs().get(songId)?.filePath != null }
    val input = com.spotifry.music.player.PipelineInput(
            origin = com.spotifry.music.player.songSource(songId, saved, c.songOrigin(songId), c.resolver.knownOrigin(songId)), readings = readings, bitrateKbps = c.streamKbps(songId, wholeMs),
            eqOn = eqOn, eqName = eqName, leveling = leveling, gainDb = c.audioTap.gainDb(), fadeSeconds = fade, speed = speed, pitch = pitch,
            trimText = trimText(trim), skipSilence = silence, spatial = spatial,
            mixerRateHz = com.spotifry.music.player.mixerRateHz(context), directSupport = com.spotifry.music.player.directSupport(context, readings),
            output = com.spotifry.music.player.outputInfo(context, codec), tuning = !tune.neutral,
        )
    AudioPipelineSheet(com.spotifry.music.player.pipelineGroups(input), onClose, com.spotifry.music.player.pipelineVerdict(input))
}

private fun clock(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

/** "Starts at 0:12 · ends at 3:01"; null for a whole song. */
internal fun trimText(t: com.spotifry.music.player.Trim?): String? {
    if (t == null || t.isWhole) return null
    return listOfNotNull(t.startMs.takeIf { it > 0 }?.let { "Starts at ${clock(it)}" }, t.endMs?.let { "ends at ${clock(it)}" }).joinToString(" · ")
}
