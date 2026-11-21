package com.spotifry.music.ui.nowplaying

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifry.music.player.AudioReadings
import com.spotifry.music.player.OutputInfo
import com.spotifry.music.player.OutputKind
import com.spotifry.music.player.OutputLook
import com.spotifry.music.player.PipelineIcon
import com.spotifry.music.player.bitsOf
import com.spotifry.music.player.codecName
import com.spotifry.music.player.hzText
import com.spotifry.music.player.outputInfo
import com.spotifry.music.ui.Icons

/** Where the sound goes now, updated when headphones come or go. */
@Composable
fun rememberOutput(): OutputInfo {
    val context = LocalContext.current
    val out by produceState(outputInfo(context), context) {
        val audio = context.getSystemService(AudioManager::class.java)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { value = outputInfo(context) }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { value = outputInfo(context) }
        }
        audio.registerAudioDeviceCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
        awaitDispose { audio.unregisterAudioDeviceCallback(callback) }
    }
    return out
}

fun outputIcon(look: OutputLook): ImageVector = when (look) {
    OutputLook.Speaker -> Icons.Output
    OutputLook.Headphones -> Icons.Headphones
    OutputLook.Earbuds -> Icons.Earbuds
}

/** "FLAC · 24-bit · 96 kHz", from the same readings as the Audio pipeline; null before anything has played. */
internal fun formatSummary(r: AudioReadings): String? {
    if (r.mime == null) return null
    return listOfNotNull(codecName(r.mime), r.decodedEncoding?.let(::bitsOf)?.let { "$it-bit" }, r.sampleRate?.let(::hzText)).joinToString(" · ")
}

/**
 * The output button's sheet: the device playing now, the way to Android's own output choice ([onOtherDevice], left out
 * when the phone has none), and the Audio pipeline. No volume (the volume keys do that) and no Cast.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutputSheet(output: OutputInfo, canPick: Boolean, format: String?, onOtherDevice: () -> Unit, onPipeline: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 24.dp)) {
            Text("Audio output", Modifier.padding(start = 24.dp, end = 24.dp, bottom = 14.dp), style = SheetTitle)
            val kind = when (output.kind) { OutputKind.Phone -> "This phone"; OutputKind.Bluetooth -> "Bluetooth"; OutputKind.Usb -> "USB"; OutputKind.Wired -> "Wired" }
            OutputCard(outputIcon(output.look), output.name, "$kind · playing here", on = true)
            if (canPick) OutputCard(pipelineIcon(PipelineIcon.Bluetooth), "Another device", "Bluetooth, USB or wired headphones", onClick = onOtherDevice)
            OutputCard(pipelineIcon(PipelineIcon.Equalizer), "Audio pipeline", format ?: "Every step from the file to your ears", onClick = onPipeline)
        }
    }
}

@Composable
private fun OutputCard(icon: ImageVector, title: String, detail: String, on: Boolean = false, onClick: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    fun mix(a: Float) = scheme.onSurface.copy(alpha = a).compositeOver(scheme.surface)
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp).fillMaxWidth().defaultMinSize(minHeight = 76.dp).clip(RoundedCornerShape(22.dp))
            .background(mix(if (on) .13f else .06f)).let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        Arrangement.spacedBy(14.dp), Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(mix(.10f)), Alignment.Center) { Icon(icon, null, Modifier.size(24.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, Modifier.padding(top = 2.dp), fontSize = 14.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (on) Icon(Icons.Check, "Playing here", Modifier.size(22.dp))
        else Icon(Icons.Chevron, null, Modifier.size(22.dp), tint = scheme.onSurfaceVariant)
    }
}

internal val SheetTitle @Composable get() = MaterialTheme.typography.headlineSmall.copy(fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
