package com.spotifry.music.ui.nowplaying

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.spotifry.music.player.PipelineIcon

/** Thin outline pictures for the Audio pipeline rows, drawn on a 24 grid with a 1.9 line. */
private fun line(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    paths.forEach { addPath(addPathNodes(it), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
}.build()

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} $cy a$r $r 0 1 0 ${2 * r} 0 a$r $r 0 1 0 ${-2 * r} 0"

private val icons: Map<PipelineIcon, ImageVector> by lazy {
    mapOf(
        PipelineIcon.Source to line("source", "M4 12a8 8 0 0 1 16 0", "M7.5 12a4.5 4.5 0 0 1 9 0", circle(12f, 12.5f, 1.6f)),
        PipelineIcon.File to line("file", "M7 3h7l4 4v14H7z", "M14 3v4h4"),
        PipelineIcon.Decoder to line("decoder", "M6 6h12v12H6z", "M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4"),
        PipelineIcon.Format to line("format", "M3 12h4l3-7 4 14 3-7h4"),
        PipelineIcon.Equalizer to line("equalizer", "M5 4v16M12 4v16M19 4v16", circle(5f, 9f, 2f), circle(12f, 15f, 2f), circle(19f, 8f, 2f)),
        PipelineIcon.Volume to line("volume", "M4 9v6h4l5 4V5L8 9z", "M16.5 8.5a5 5 0 0 1 0 7"),
        PipelineIcon.Fade to line("fade", "M3 17c4 0 5-10 9-10s5 10 9 10"),
        PipelineIcon.Speed to line("speed", "M4 17a8 8 0 1 1 16 0", "M12 17l4-5"),
        PipelineIcon.Trim to line("trim", circle(6f, 7f, 2.5f), circle(6f, 17f, 2.5f), "M8 8.5L20 18M8 15.5L20 6"),
        PipelineIcon.Silence to line("silence", "M3 12h5M16 12h5M10 8v8M14 8v8"),
        PipelineIcon.Spatial to line("spatial", "M5 12a7 7 0 0 1 14 0", "M2 12a10 10 0 0 1 20 0", circle(12f, 14f, 1.6f)),
        PipelineIcon.Mixer to line("mixer", "M3 8h6l3 8h9", "M3 16h6l3-8h9"),
        PipelineIcon.Direct to line("direct", "M3 12h15M13 6l6 6-6 6"),
        PipelineIcon.Buffer to line("buffer", "M4 7h16M4 12h16M4 17h10"),
        PipelineIcon.Speaker to line("speaker", "M6 3h12v18H6z", circle(12f, 14.5f, 3f), circle(12f, 7.5f, .8f)),
        PipelineIcon.Bluetooth to line("bluetooth", "M7 7l10 10-5 4V3l5 4L7 17"),
        PipelineIcon.Usb to line("usb", "M12 3v14", circle(12f, 19f, 2f), "M12 9l5 2v2M12 12L7 10v-1"),
        PipelineIcon.Headphones to line("headphones", "M4 14v-2a8 8 0 0 1 16 0v2", "M4 14h3v6H4zM17 14h3v6h-3z"),
        PipelineIcon.Receives to line("receives", "M4 9v6M8 6v12M12 3v18M16 7v10M20 10v4"),
        PipelineIcon.Supports to line("supports", "M4 7l2 2 3.5-3.5M4 16l2 2 3.5-3.5M13 8h7M13 17h7"),
    )
}

fun pipelineIcon(i: PipelineIcon): ImageVector = icons.getValue(i)
