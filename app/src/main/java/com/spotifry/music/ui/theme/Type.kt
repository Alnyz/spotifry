package com.spotifry.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.spotifry.music.R

/** Albert Sans (variable). Bold for titles and section names, regular for reading. */
@OptIn(ExperimentalTextApi::class)
val Albert = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(R.font.albertsans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

private fun bold(size: Int, line: Int) =
    TextStyle(fontFamily = Albert, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = line.sp, letterSpacing = (-0.02f * size).sp)
private fun text(size: Int, line: Int, w: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Albert, fontWeight = w, fontSize = size.sp, lineHeight = line.sp)

val MusicTypography = Typography(
    displayLarge = bold(57, 60), displayMedium = bold(44, 48), displaySmall = bold(34, 40),
    headlineLarge = bold(30, 34), headlineMedium = bold(28, 32), headlineSmall = bold(21, 26),
    titleLarge = text(20, 26), titleMedium = text(16, 22, FontWeight.SemiBold), titleSmall = text(15, 20, FontWeight.SemiBold),
    bodyLarge = text(16, 22), bodyMedium = text(14, 20), bodySmall = text(13, 18),
    labelLarge = text(15, 20, FontWeight.SemiBold), labelMedium = text(12, 16, FontWeight.Medium), labelSmall = text(11, 16, FontWeight.Medium),
)
