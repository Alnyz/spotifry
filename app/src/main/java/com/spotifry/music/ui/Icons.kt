package com.spotifry.music.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
    pathData = addPathNodes(path), fill = SolidColor(Color.Black),
).build()

/** Icons drawn from the same paths as design/mockup.html. */
object Icons {
    val Home = icon("home", "M12 3 3 10.5V21h6v-6h6v6h6V10.5z")
    val Saved = icon("saved", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zm0 4v6.2l2.3-2.3 1.4 1.4L12 16l-3.7-3.7 1.4-1.4 2.3 2.3V7z")
    val List = icon("list", "M3 6h14v2H3zm0 5h14v2H3zm0 5h9v2H3zm15-5v5.3a2.7 2.7 0 1 0 2 2.6V8h3V6h-5z")
    val Search = icon("search", "M10 3a7 7 0 0 1 5.6 11.2l5.1 5.1-1.4 1.4-5.1-5.1A7 7 0 1 1 10 3zm0 2a5 5 0 1 0 0 10 5 5 0 0 0 0-10z")
    val Play = icon("play", "M8 5v14l11-7z")
    val Pause = icon("pause", "M6 5h4v14H6zm8 0h4v14h-4z")
    val Next = icon("next", "M6 6l9 6-9 6zm10 0h2v12h-2z")
    val Prev = icon("prev", "M18 6l-9 6 9 6zM6 6h2v12H6z")
    // shuffle and repeat use rounded ends (Material "Round" shapes), softer than the square-cut arrows
    val Shuffle = icon("shuffle", "M10.59 9.17 6.12 4.7a.996.996 0 1 0-1.41 1.41l4.46 4.46 1.42-1.4zm4.76-4.32 1.19 1.19L4.7 17.88a.996.996 0 1 0 1.41 1.41L17.96 7.46l1.19 1.19a.5.5 0 0 0 .85-.36V4.5c0-.28-.22-.5-.5-.5h-3.79a.5.5 0 0 0-.36.85zm-.52 8.56-1.41 1.41 3.13 3.13-1.2 1.2a.5.5 0 0 0 .36.85h3.79c.28 0 .5-.22.5-.5v-3.79c0-.45-.54-.67-.85-.35l-1.19 1.19-3.13-3.14z")
    val Repeat = icon("repeat", "M7 7h10v1.79c0 .45.54.67.85.35l2.79-2.79c.2-.2.2-.51 0-.71l-2.79-2.79a.5.5 0 0 0-.85.36V5H6c-.55 0-1 .45-1 1v4c0 .55.45 1 1 1s1-.45 1-1V7zm10 10H7v-1.79c0-.45-.54-.67-.85-.35l-2.79 2.79c-.2.2-.2.51 0 .71l2.79 2.79a.5.5 0 0 0 .85-.36V19h11c.55 0 1-.45 1-1v-4c0-.55-.45-1-1-1s-1 .45-1 1v3z")
    val RepeatOne = icon("repeat_one", "M7 7h10v1.79c0 .45.54.67.85.35l2.79-2.79c.2-.2.2-.51 0-.71l-2.79-2.79a.5.5 0 0 0-.85.36V5H6c-.55 0-1 .45-1 1v4c0 .55.45 1 1 1s1-.45 1-1V7zm10 10H7v-1.79c0-.45-.54-.67-.85-.35l-2.79 2.79c-.2.2-.2.51 0 .71l2.79 2.79a.5.5 0 0 0 .85-.36V19h11c.55 0 1-.45 1-1v-4c0-.55-.45-1-1-1s-1 .45-1 1v3zm-4-2.75V9.81c0-.45-.36-.81-.81-.81a.74.74 0 0 0-.36.09l-1.49.74c-.21.1-.34.32-.34.55 0 .34.28.62.62.62h.88v3.25c0 .41.34.75.75.75s.75-.34.75-.75z")
    val Down = icon("down", "M7.4 9 12 13.6 16.6 9 18 10.4l-6 6-6-6z")
    val More = icon("more", "M12 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 6a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 6a2 2 0 1 0 0-4 2 2 0 0 0 0 4z")
    val Download = icon("download", "M11 4h2v8.2l2.6-2.6L17 11l-5 5-5-5 1.4-1.4L11 12.2zM5 18h14v2H5z")
    val Queue = icon("queue", "M3 6h12v2H3zm0 5h12v2H3zm0 5h8v2H3zm13-1v-3l5 3.5-5 3.5v-3z")
    val Plus = icon("plus", "M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z")
    val Trash = icon("trash", "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z")
    val Mix = icon("mix", "M3 10h2v4H3zm4-3h2v10H7zm4-4h2v18h-2zm4 5h2v8h-2zm4 2h2v4h-2z")
    val Check = icon("check", "M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z")
    val Link = icon("link", "M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z")
    val Close = icon("close", "M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z")
    val Sort = icon("sort", "M3 6h18v2H3zm3 5h12v2H6zm4 5h4v2h-4z")
    val DragHandle = icon("drag", "M4 8h16v2H4zm0 6h16v2H4z")
    val Reorder = icon("reorder", "M8 3 4 7h3v8h2V7h3zm9 6h-2v8h-3l4 4 4-4h-3z")
    val Upload = icon("upload", "M11 20V8.8l-4.3 4.3-1.4-1.4L12 5l6.7 6.7-1.4 1.4L13 8.8V20z")
    val Speed = icon("speed", "M12 4a9 9 0 0 0-7.8 13.5l1.7-1A7 7 0 1 1 19 12h2a9 9 0 0 0-9-8zm4.6 3.4-5.4 3.9a2 2 0 1 0 2.4 2.4z")
    val Clock = icon("clock", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zm0 2a7 7 0 1 1 0 14 7 7 0 0 1 0-14zm-1 2v6l5 3 1-1.7-4-2.4V7z")
    val Settings = icon("settings", "M19.4 13a7.6 7.6 0 0 0 0-2l2.1-1.6-2-3.5-2.5 1a7.4 7.4 0 0 0-1.7-1L15 3h-4l-.4 2.7a7.4 7.4 0 0 0-1.7 1l-2.5-1-2 3.5L6.6 11a7.6 7.6 0 0 0 0 2l-2.1 1.6 2 3.5 2.5-1c.5.4 1.1.7 1.7 1L11 21h4l.4-2.7c.6-.3 1.2-.6 1.7-1l2.5 1 2-3.5zM13 15.5A3.5 3.5 0 1 1 13 8.5a3.5 3.5 0 0 1 0 7z")
    val Back = icon("back", "M20 11H7.8l5.6-5.6L12 4l-8 8 8 8 1.4-1.4L7.8 13H20z")
    val Note = icon("note", "M12 3v10.6A4 4 0 1 0 14 17V7h4V3z")
    val Phone = icon("phone", "M7 1h10a2 2 0 0 1 2 2v18a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V3a2 2 0 0 1 2-2zm0 4v14h10V5z")
    val Chevron = icon("chevron", "M9.4 6 8 7.4l4.6 4.6L8 16.6 9.4 18l6-6z")
}
