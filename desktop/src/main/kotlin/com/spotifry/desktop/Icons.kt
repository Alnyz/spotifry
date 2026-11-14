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
    /** Apple's Lossless mark: three ribbons of one wave, wider than tall (38.14 by 24). For a lossless song's sound label. */
    val Lossless: ImageVector = ImageVector.Builder("lossless", 38.14.dp, 24.dp, 38.14f, 24f).addPath(
        addPathNodes("M6.95 0.02 L7.50 0.01 L8.06 0.10 L9.33 0.64 L10.43 1.52 L11.70 3.16 L12.20 3.99 L13.04 5.65 L13.92 7.78 L14.74 10.04 L16.96 16.91 L17.91 19.35 L18.25 20.07 L19.07 21.46 L19.68 22.18 L20.04 22.50 L20.40 22.69 L20.94 22.85 L22.02 22.77 L22.62 23.28 L22.67 23.46 L22.59 23.59 L21.82 23.86 L21.12 23.96 L20.22 23.99 L19.33 23.85 L18.44 23.44 L17.78 22.95 L16.90 22.05 L15.98 20.79 L15.26 19.55 L14.66 18.32 L13.68 15.96 L11.94 10.88 L11.81 10.28 L11.05 7.94 L10.11 5.37 L9.67 4.36 L8.63 2.52 L7.84 1.66 L7.29 1.32 L6.80 1.16 L6.34 1.13 L5.42 1.39 L4.48 2.05 L3.89 2.74 L3.32 3.59 L2.49 5.24 L1.57 7.94 L0.82 11.43 L0.48 13.75 L0.30 13.94 L0.15 13.93 L0.04 13.85 L0.00 13.59 L0.33 11.48 L0.89 8.66 L1.66 5.78 L2.13 4.49 L2.63 3.34 L2.95 2.75 L3.64 1.72 L4.34 1.02 L4.77 0.68 L5.70 0.19 L6.24 0.07ZM11.91 0.02 L12.97 0.02 L13.59 0.15 L14.13 0.38 L15.06 0.97 L15.77 1.64 L16.72 2.85 L17.70 4.52 L18.99 7.37 L19.93 9.99 L22.17 16.86 L22.82 18.58 L23.44 19.99 L24.28 21.45 L25.14 22.36 L25.57 22.65 L26.13 22.83 L27.32 22.78 L27.82 23.28 L27.84 23.44 L27.74 23.56 L26.88 23.90 L26.26 23.99 L25.16 23.97 L24.41 23.77 L23.62 23.41 L23.04 23.00 L22.18 22.11 L21.46 21.20 L20.91 20.35 L20.25 19.07 L19.68 17.88 L18.88 15.90 L15.83 6.73 L15.21 5.11 L14.38 3.36 L13.51 2.12 L12.98 1.61 L12.66 1.40 L11.92 1.14 L10.89 1.25 L10.32 0.71 L10.31 0.53 L10.48 0.39 L11.15 0.12ZM16.98 0.02 L17.86 0.00 L18.65 0.12 L19.56 0.51 L20.74 1.38 L21.51 2.24 L22.28 3.38 L22.79 4.26 L23.60 5.88 L24.44 7.94 L24.98 9.40 L27.24 16.43 L28.30 19.20 L29.30 21.15 L30.07 22.11 L30.50 22.49 L30.79 22.65 L31.22 22.80 L31.76 22.85 L32.28 22.78 L32.79 22.57 L33.76 21.84 L34.73 20.63 L35.37 19.43 L35.72 18.60 L36.66 15.72 L37.14 13.67 L37.49 11.61 L37.65 10.30 L37.83 10.07 L37.96 10.06 L38.09 10.15 L38.14 10.38 L37.98 11.66 L37.58 13.51 L37.44 14.52 L36.97 16.60 L36.19 19.09 L35.64 20.43 L35.00 21.60 L34.33 22.46 L33.56 23.22 L32.95 23.59 L31.99 23.93 L30.91 24.00 L29.78 23.86 L28.91 23.45 L28.42 23.10 L27.65 22.41 L26.93 21.56 L26.00 20.09 L25.27 18.71 L24.08 15.84 L21.28 7.32 L20.49 5.21 L19.53 3.23 L18.71 2.10 L17.93 1.41 L17.65 1.28 L16.98 1.12 L16.16 1.23 L15.54 0.71 L15.53 0.53 L15.67 0.41 L16.34 0.13ZM13.36 2.95 L13.46 2.96 L13.56 3.07 L13.77 3.52 L13.35 4.47 L13.20 4.57 L13.02 4.49 L12.93 4.36 L12.84 3.88 L13.23 3.05ZM8.12 2.98 L8.24 2.99 L8.40 3.13 L8.52 3.41 L8.53 3.57 L7.76 5.13 L7.28 6.45 L6.83 7.91 L6.32 10.02 L5.96 11.89 L5.71 13.72 L5.60 13.90 L5.39 13.95 L5.26 13.85 L5.22 13.67 L5.48 11.89 L5.95 9.38 L6.51 7.01 L6.83 5.93 L7.60 3.88 L7.99 3.10ZM27.38 10.07 L27.55 10.06 L27.65 10.13 L27.71 10.56 L27.30 12.67 L27.18 13.67 L27.06 13.87 L26.96 13.80 L26.87 13.59 L26.81 13.03 L27.21 10.30ZM32.62 10.07 L32.77 10.06 L32.87 10.14 L32.92 10.51 L32.61 12.43 L31.94 15.83 L31.12 18.63 L30.17 20.94 L30.02 21.05 L29.89 20.99 L29.70 20.74 L29.64 20.35 L30.27 19.14 L31.06 17.06 L31.90 13.67 L32.43 10.28ZM11.10 10.15 L11.20 10.14 L11.30 10.35 L11.36 11.10 L10.93 13.75 L10.84 13.90 L10.69 13.94 L10.51 13.85 L10.46 13.62 L10.67 12.02 L10.94 10.58ZM24.94 19.40 L25.09 19.45 L25.34 19.99 L24.95 20.89 L24.77 21.01 L24.46 20.75 L24.39 20.40Z"), fill = androidx.compose.ui.graphics.SolidColor(Color.Black), pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd,
    ).build()
    /** For a lossy song's sound label. */
    val Headphones = icon("headphones", "M12 1c-4.97 0-9 4.03-9 9v7c0 1.66 1.34 3 3 3h3v-8H5v-2c0-3.87 3.13-7 7-7s7 3.13 7 7v2h-4v8h3c1.66 0 3-1.34 3-3v-7c0-4.97-4.03-9-9-9z")
    /** Scissors: the sign of a trimmed song. */
    val Cut = icon("cut", "M9.64 7.64c.23-.5.36-1.05.36-1.64 0-2.21-1.79-4-4-4S2 3.79 2 6s1.79 4 4 4c.59 0 1.14-.13 1.64-.36L10 12l-2.36 2.36C7.14 14.13 6.59 14 6 14c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4c0-.59-.13-1.14-.36-1.64L12 14l7 7h3v-1L9.64 7.64zM6 8c-1.1 0-2-.89-2-2s.9-2 2-2 2 .89 2 2-.9 2-2 2zm0 12c-1.1 0-2-.89-2-2s.9-2 2-2 2 .89 2 2-.9 2-2 2zM19 3l-6 6 2 2 7-7V3h-3z")
    val Plus = icon("plus", "M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z")
    val Check = icon("check", "M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z")
    val DragHandle = icon("drag", "M4 9h16v2H4zm0 4h16v2H4z")
}
