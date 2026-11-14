package com.spotifry.desktop

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

/** Copied from the phone (ui/home/MoodArt.kt), so the tiles look the same. How strongly a tile's picture shows over its colour. MoodsTest checks the white name stays readable on top of it. */
internal const val ART_ALPHA = 0.22f

/**
 * Draws a tile's picture at its right edge, in a 100 by 100 square as tall as the tile. Pictures are drawn solid
 * in one layer, faded together, so overlapping shapes leave no seams and [cut] can punch real holes.
 */
internal fun DrawScope.moodArt(art: DrawScope.() -> Unit) = drawIntoCanvas { canvas ->
    canvas.saveLayer(size.toRect(), Paint().apply { alpha = ART_ALPHA })
    val unit = size.height / 100f
    withTransform({
        translate(left = size.width - size.height)
        scale(unit, unit, pivot = Offset.Zero)
    }, art)
    canvas.restore()
}

private fun DrawScope.dot(x: Float, y: Float, r: Float) = drawCircle(Color.White, r, Offset(x, y))
private fun DrawScope.ring(x: Float, y: Float, r: Float, w: Float) = drawCircle(Color.White, r, Offset(x, y), style = Stroke(w))
private fun DrawScope.block(x: Float, y: Float, w: Float, h: Float, corner: Float = 0f) =
    drawRoundRect(Color.White, Offset(x, y), Size(w, h), CornerRadius(corner))
private fun DrawScope.cut(x: Float, y: Float, r: Float) = drawCircle(Color.Black, r, Offset(x, y), blendMode = BlendMode.Clear)
private fun DrawScope.cutRing(x: Float, y: Float, r: Float, w: Float) = drawCircle(Color.Black, r, Offset(x, y), style = Stroke(w), blendMode = BlendMode.Clear)
private fun DrawScope.cutBlock(x: Float, y: Float, w: Float, h: Float, corner: Float = 0f) =
    drawRoundRect(Color.Black, Offset(x, y), Size(w, h), CornerRadius(corner), blendMode = BlendMode.Clear)

internal fun DrawScope.waves() = listOf(26f, 46f, 66f).forEach { y ->
    drawPath(
        Path().apply {
            moveTo(0f, y)
            repeat(4) { relativeQuadraticTo(15f, -14f, 30f, 0f); relativeQuadraticTo(15f, 14f, 30f, 0f) }
        },
        Color.White, style = Stroke(6f, cap = StrokeCap.Round),
    )
}

internal fun DrawScope.target() { ring(60f, 42f, 38f, 6f); ring(60f, 42f, 23f, 6f); dot(60f, 42f, 9f) }

internal fun DrawScope.dumbbell() = rotate(-25f, Offset(58f, 42f)) {
    block(22f, 37f, 72f, 10f, 3f)
    block(20f, 18f, 12f, 48f, 4f); block(35f, 24f, 9f, 36f, 3f)
    block(84f, 18f, 12f, 48f, 4f); block(72f, 24f, 9f, 36f, 3f)
}

internal fun DrawScope.confetti() {
    listOf(Triple(22f, 20f, 4f), Triple(48f, 46f, 5f), Triple(70f, 14f, 6f), Triple(88f, 56f, 4f), Triple(36f, 74f, 3f), Triple(66f, 82f, 5f)).forEach { (x, y, r) -> dot(x, y, r) }
    listOf(Triple(38f, 26f, 30f), Triple(60f, 34f, -25f), Triple(84f, 30f, 50f), Triple(26f, 52f, -40f), Triple(70f, 60f, 20f), Triple(90f, 82f, -30f)).forEach { (x, y, a) ->
        rotate(a, Offset(x, y)) { block(x - 3f, y - 7f, 6f, 14f, 1.5f) }
    }
}

internal fun DrawScope.rain() {
    dot(42f, 30f, 15f); dot(62f, 24f, 20f); dot(82f, 32f, 14f); block(27f, 30f, 69f, 16f, 8f)
    listOf(42f to 62f, 62f to 74f, 82f to 62f).forEach { (x, y) ->
        drawPath(
            Path().apply {
                moveTo(x, y - 11f)
                cubicTo(x + 9f, y + 1f, x + 7f, y + 10f, x, y + 10f)
                cubicTo(x - 7f, y + 10f, x - 9f, y + 1f, x, y - 11f)
            },
            Color.White,
        )
    }
}

internal fun DrawScope.moon() {
    dot(56f, 42f, 32f); cut(70f, 32f, 28f)
    dot(78f, 28f, 3.5f); dot(92f, 52f, 2.5f); dot(70f, 62f, 2f); dot(18f, 16f, 2.5f)
}

internal fun DrawScope.sun() {
    dot(62f, 42f, 19f)
    repeat(8) { rotate(45f * it, Offset(62f, 42f)) { block(59f, 4f, 6f, 14f, 3f) } }
}

internal fun DrawScope.heart() = rotate(10f, Offset(60f, 46f)) {
    drawPath(
        Path().apply {
            moveTo(60f, 84f)
            cubicTo(16f, 54f, 20f, 14f, 42f, 14f)
            cubicTo(51f, 14f, 58f, 20f, 60f, 30f)
            cubicTo(62f, 20f, 69f, 14f, 78f, 14f)
            cubicTo(100f, 14f, 104f, 54f, 60f, 84f)
            close()
        },
        Color.White,
    )
}

internal fun DrawScope.microphone() = rotate(30f, Offset(60f, 46f)) {
    dot(60f, 22f, 18f)
    block(52f, 44f, 16f, 52f, 6f)
}

/** A gendang: a barrel drum standing up, its top skin and lacing cut out. */
internal fun DrawScope.drum() = rotate(10f, Offset(60f, 50f)) {
    drawOval(Color.White, Offset(26f, 8f), Size(68f, 26f))
    block(26f, 21f, 68f, 66f, 10f)
    drawOval(Color.Black, Offset(26f, 8f), Size(68f, 26f), style = Stroke(3f), blendMode = BlendMode.Clear)
    drawPath(
        Path().apply {
            moveTo(30f, 44f)
            repeat(6) { i -> relativeLineTo(10f, if (i % 2 == 0) 34f else -34f) }
        },
        Color.Black, style = Stroke(3f), blendMode = BlendMode.Clear,
    )
}

internal fun DrawScope.cassette() {
    block(14f, 20f, 92f, 60f, 8f)
    cutBlock(28f, 32f, 64f, 24f, 12f)
    dot(44f, 44f, 7f); dot(76f, 44f, 7f)
    cutBlock(36f, 66f, 48f, 5f, 2f)
}

internal fun DrawScope.speaker() {
    block(30f, 4f, 58f, 92f, 8f)
    cut(59f, 62f, 22f); dot(59f, 62f, 13f)
    cut(59f, 25f, 12f); dot(59f, 25f, 6f)
}

internal fun DrawScope.vinyl() {
    dot(62f, 42f, 40f)
    cutRing(62f, 42f, 32f, 2f); cutRing(62f, 42f, 25f, 2f)
    cut(62f, 42f, 12f); dot(62f, 42f, 4f)
}

internal fun DrawScope.bolt() = drawPath(
    Path().apply {
        moveTo(72f, 4f)
        lineTo(32f, 54f); lineTo(56f, 54f); lineTo(42f, 96f); lineTo(90f, 36f); lineTo(64f, 36f)
        close()
    },
    Color.White,
)

internal fun DrawScope.pianoKeys() {
    block(10f, 14f, 100f, 72f, 4f)
    listOf(30f, 50f, 70f, 90f).forEach { cutBlock(it - 1f, 14f, 2f, 72f) }
    listOf(30f, 50f, 90f).forEach { cutBlock(it - 6f, 14f, 12f, 42f, 2f) }
}

internal fun DrawScope.levelBars() = listOf(34f, 62f, 86f, 52f, 72f).forEachIndexed { i, h ->
    block(12f + 18f * i, 94f - h, 12f, h, 3f)
}
