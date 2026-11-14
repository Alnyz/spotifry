package com.spotifry.music.player

/**
 * Headphone crossfeed: a little of each side reaches the other ear, dulled the way a head dulls it, like listening to speakers.
 * [shadowHz] is where the dulling starts, [crossDb] how much quieter the crossing sound is, [amount] 0..1 how much of the
 * effect is mixed in.
 */
data class SpatialSetting(val shadowHz: Int, val crossDb: Int, val amount: Int) {
    companion object {
        val LIGHT = SpatialSetting(700, 9, 60)
        val NATURAL = SpatialSetting(700, 6, 100)
        val STRONG = SpatialSetting(550, 4, 100)
        val PRESETS = listOf("Light" to LIGHT, "Natural" to NATURAL, "Strong" to STRONG)
    }
}
