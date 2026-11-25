package com.spotifry.music.data.discord

import com.spotifry.music.player.PlayerUiState
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** The id of "Spotifry" on Discord's developer site. Public, not a secret. */
const val DISCORD_APP_ID = "1575113956924588182"

/** The app's logo, which Discord fetches itself, so it has to be a public picture. */
const val DISCORD_LOGO = "https://raw.githubusercontent.com/Alnyz/spotifry/main/desktop/packaging/spotifry.png"

private const val SITE = "https://alnyz.github.io/spotifry/"
private const val BUTTON = "Get Spotifry"

/** What the profile shows. [startMs] is the clock time the song would have started at, which Discord counts from. */
data class DiscordActivity(val title: String, val artist: String, val cover: String?, val startMs: Long, val endMs: Long?, val link: String? = null) {
    /** The same card: a few hundred ms of drift in the start is not a change, a seek is. */
    fun sameAs(other: DiscordActivity?) = other != null && copy(startMs = 0, endMs = null) == other.copy(startMs = 0, endMs = null) &&
        abs(startMs - other.startMs) < 3_000 && (endMs == null) == (other.endMs == null)

    // Discord refuses texts shorter than 2 or longer than 128 characters
    private fun fit(text: String) = text.take(128).padEnd(2)

    private fun card(): JSONObject {
        val o = JSONObject().put("type", 2).put("details", fit(title))
            .put("timestamps", JSONObject().put("start", startMs).apply { endMs?.let { put("end", it) } })
        // 1: the member list says "Listening to <artist>" rather than "Listening to Spotifry"
        if (artist.isNotBlank()) o.put("state", fit(artist)).put("status_display_type", 1)
        return o
    }

    /** Discord allows two. A song from the user's own files and a radio station have no link to listen to. */
    private fun buttons() = listOfNotNull(link?.let { "Listen to this song" to it }, BUTTON to SITE)

    /** For the Discord app on the same computer, which takes pictures as plain addresses. */
    fun json(): JSONObject {
        // the logo sits on the cover's corner, or takes its place when there is no cover to show
        // (no large_text: Discord would show it as a third line under the artist)
        val assets = JSONObject().put("large_image", cover ?: DISCORD_LOGO)
        if (cover != null) assets.put("small_image", DISCORD_LOGO).put("small_text", "Spotifry")
        // Discord shows buttons to other people only, never on your own card
        return card().put("assets", assets).put("buttons", JSONArray(buttons().map { (label, url) -> JSONObject().put("label", label).put("url", url) }))
    }

    /** For a phone's own connection to Discord: [large] and [small] are Discord's names for the pictures, not addresses. */
    fun gatewayJson(large: String?, small: String?): JSONObject {
        val assets = JSONObject()
        if (large != null) assets.put("large_image", large)
        if (small != null) assets.put("small_image", small).put("small_text", "Spotifry")
        return card().put("name", "Spotifry").put("application_id", DISCORD_APP_ID).put("assets", assets)
            .put("buttons", JSONArray(buttons().map { it.first })).put("metadata", JSONObject().put("button_urls", JSONArray(buttons().map { it.second })))
    }

    companion object {
        private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")

        /** Covers kept on this device can't be shown: Discord fetches the picture itself. */
        fun of(id: String, title: String, artist: String, cover: String?, positionMs: Long, durationMs: Long, now: Long): DiscordActivity {
            val start = now - positionMs
            return DiscordActivity(title, artist, cover?.takeIf { it.startsWith("https://") }, start, if (durationMs > 0) start + durationMs else null,
                // only a YouTube id makes a link: local files and radio stations have a "local:" or "radio:" one
                id.takeIf(YOUTUBE_ID::matches)?.let { "https://youtu.be/$it" })
        }

        /** Null when nothing should show: nothing playing, paused or finished. */
        fun of(s: PlayerUiState, positionMs: Long, now: Long): DiscordActivity? {
            val song = s.current ?: return null
            if (!s.wantsPlay || s.ended || s.failed) return null
            return of(song.id, song.title, song.artist, song.thumbnailUrl, positionMs, s.durationMs, now)
        }
    }
}
