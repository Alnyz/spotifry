package com.spotifry.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Icons as SVG path data on a 24 by 24 grid, the same shapes as the phone app's. */
object Icons {
    private fun icon(name: String, d: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(addPathNodes(d), fill = SolidColor(Color.Black)).build()

    val Play = icon("play", "M8 5v14l11-7z")
    val Pause = icon("pause", "M6 5h4v14H6zm8 0h4v14h-4z")
    val Next = icon("next", "M6 6l9 6-9 6zm10 0h2v12h-2z")
    val Prev = icon("prev", "M18 6l-9 6 9 6zM6 6h2v12H6z")
    val Home = icon("home", "M12 3 3 10.5V21h6v-6h6v6h6V10.5z")
    val Search = icon("search", "M10 3a7 7 0 0 1 5.6 11.2l5.1 5.1-1.4 1.4-5.1-5.1A7 7 0 1 1 10 3zm0 2a5 5 0 1 0 0 10 5 5 0 0 0 0-10z")
    val Saved = icon("saved", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zm0 4v6.2l2.3-2.3 1.4 1.4L12 16l-3.7-3.7 1.4-1.4 2.3 2.3V7z")
    val Playlists = icon("playlists", "M3 6h14v2H3zm0 5h14v2H3zm0 5h9v2H3zm15-5v5.3a2.7 2.7 0 1 0 2 2.6V8h3V6h-5z")
    val Shuffle = icon("shuffle", "M10.6 9.2 5.4 4 4 5.4l5.2 5.2zM14.5 4l2 2L4 18.6 5.4 20 18 7.5l2 2V4zm.3 9.4-1.4 1.4 3.1 3.1-2 2.1H20v-5.5l-2 2z")
    val Repeat = icon("repeat", "M7 7h10v3l4-4-4-4v3H5v6h2zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2z")
    val Volume = icon("volume", "M3 9v6h4l5 5V4L7 9zm13.5 3a4.5 4.5 0 0 0-2.5-4v8a4.5 4.5 0 0 0 2.5-4z")
    val Settings = icon("settings", "M19.4 13a7.6 7.6 0 0 0 0-2l2.1-1.6-2-3.5-2.5 1a7.4 7.4 0 0 0-1.7-1L15 3h-4l-.4 2.7a7.4 7.4 0 0 0-1.7 1l-2.5-1-2 3.5L6.6 11a7.6 7.6 0 0 0 0 2l-2.1 1.6 2 3.5 2.5-1c.5.4 1.1.7 1.7 1L11 21h4l.4-2.7c.6-.3 1.2-.6 1.7-1l2.5 1 2-3.5zM13 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z")
    val More = icon("more", "M12 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 6a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 6a2 2 0 1 0 0-4 2 2 0 0 0 0 4z")
    val Download = icon("download", "M11 4h2v8.2l2.6-2.6L17 11l-5 5-5-5 1.4-1.4L11 12.2zM5 18h14v2H5z")
    val Lyrics = icon("lyrics", "M4 5h16v2H4zm0 4h16v2H4zm0 4h10v2H4z")
    val Queue = icon("queue", "M3 6h14v2H3zm0 5h14v2H3zm0 5h9v2H3zm12 0v-2l6 3-6 3z")
    /** The one mark for Smart shuffle's suggestions, everywhere. */
    val Sparkle = icon("sparkle", "M12 3c.8 5.4 3.6 8.3 9 9-5.4.8-8.2 3.6-9 9-.8-5.4-3.6-8.2-9-9 5.4-.7 8.2-3.6 9-9z")
    /** The shuffle arrows a touch smaller, with the sparkle in the top-left corner. */
    val SmartShuffle = ImageVector.Builder("smart_shuffle", 24.dp, 24.dp, 24f, 24f)
        .addGroup(scaleX = .82f, scaleY = .82f, translationX = 4.2f, translationY = 4.2f)
        .addPath(addPathNodes("M10.6 9.2 5.4 4 4 5.4l5.2 5.2zM14.5 4l2 2L4 18.6 5.4 20 18 7.5l2 2V4zm.3 9.4-1.4 1.4 3.1 3.1-2 2.1H20v-5.5l-2 2z"), fill = SolidColor(Color.Black)).clearGroup()
        .addPath(addPathNodes("M5 .8c.35 2.3 1.4 3.35 3.7 3.7-2.3.35-3.35 1.4-3.7 3.7C4.65 5.9 3.6 4.85 1.3 4.5 3.6 4.15 4.65 3.1 5 .8z"), fill = SolidColor(Color.Black))
        .build()
    /** Sound bars, for Now playing in the top row. */
    val Wave = icon("wave", "M3 10h2v4H3zm4-4h2v12H7zm4-3h2v18h-2zm4 5h2v8h-2zm4 2h2v4h-2z")
    /** The lossless mark: a damped wave, strong at the start and fading out. */
    val Lossless: ImageVector = ImageVector.Builder("lossless", 24.dp, 24.dp, 24f, 24f).addPath(
        addPathNodes("M2.50 2.70 L2.71 3.18 L2.92 4.19 L3.13 5.64 L3.34 7.42 L3.56 9.42 L3.77 11.49 L3.98 13.50 L4.19 15.34 L4.40 16.90 L4.61 18.09 L4.82 18.86 L5.03 19.17 L5.24 19.02 L5.46 18.44 L5.67 17.49 L5.88 16.24 L6.09 14.77 L6.30 13.20 L6.51 11.61 L6.72 10.11 L6.93 8.78 L7.14 7.71 L7.36 6.94 L7.57 6.52 L7.78 6.46 L7.99 6.74 L8.20 7.33 L8.41 8.19 L8.62 9.26 L8.83 10.45 L9.04 11.69 L9.26 12.90 L9.47 14.02 L9.68 14.96 L9.89 15.69 L10.10 16.16 L10.31 16.35 L10.52 16.27 L10.73 15.93 L10.94 15.35 L11.16 14.59 L11.37 13.70 L11.58 12.73 L11.79 11.76 L12.00 10.83 L12.21 10.01 L12.42 9.35 L12.63 8.87 L12.84 8.60 L13.06 8.55 L13.27 8.72 L13.48 9.09 L13.69 9.62 L13.90 10.28 L14.11 11.02 L14.32 11.81 L14.53 12.57 L14.74 13.28 L14.96 13.88 L15.17 14.34 L15.38 14.65 L15.59 14.78 L15.80 14.73 L16.01 14.52 L16.22 14.16 L16.43 13.67 L16.64 13.10 L16.86 12.48 L17.07 11.84 L17.28 11.24 L17.49 10.70 L17.70 10.26 L17.91 9.94 L18.12 9.76 L18.33 9.72 L18.54 9.83 L18.76 10.06 L18.97 10.41 L19.18 10.85 L19.39 11.35 L19.60 11.87 L19.81 12.38 L20.02 12.86 L20.23 13.27 L20.44 13.59 L20.66 13.81 L20.87 13.90 L21.08 13.88 L21.29 13.73 L21.50 13.49"), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
        strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
    ).build()
    /** For a lossy song's sound label. */
    val Headphones = icon("headphones", "M12 1c-4.97 0-9 4.03-9 9v7c0 1.66 1.34 3 3 3h3v-8H5v-2c0-3.87 3.13-7 7-7s7 3.13 7 7v2h-4v8h3c1.66 0 3-1.34 3-3v-7c0-4.97-4.03-9-9-9z")
    val Plus = icon("plus", "M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z")
    val Check = icon("check", "M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z")
    val DragHandle = icon("drag", "M4 9h16v2H4zm0 4h16v2H4z")
}
