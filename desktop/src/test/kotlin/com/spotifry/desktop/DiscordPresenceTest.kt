package com.spotifry.desktop

import com.spotifry.music.data.discord.DiscordActivity
import com.spotifry.music.data.source.Song
import com.spotifry.music.player.PlayerUiState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.DataInputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import kotlin.concurrent.thread

class DiscordPresenceTest {
    private val playing = PlayerUiState(current = Song("a", "Hujan Pagi", "Kala Biru", 211, "https://x/y.jpg"), wantsPlay = true, durationMs = 211_000)

    @Test fun showsTheSongOnlyWhilePlaying() {
        val a = DiscordActivity.of(playing, 11_000, 100_000)!!
        assertEquals(89_000L, a.startMs)
        assertEquals(300_000L, a.endMs)
        assertEquals(2, a.json().getInt("type"))
        assertNull(DiscordActivity.of(playing.copy(wantsPlay = false), 0, 0))
        assertNull(DiscordActivity.of(PlayerUiState(), 0, 0))
        // the logo goes on the cover's corner, and stands in when there is no cover
        assertTrue(a.json().getJSONObject("assets").getString("small_image").endsWith("spotifry.png"))
        assertTrue(a.copy(cover = null).json().getJSONObject("assets").getString("large_image").endsWith("spotifry.png"))
        assertEquals("https://alnyz.github.io/spotifry/", a.json().getJSONArray("buttons").getJSONObject(0).getString("url"))
        // large_text would show as a third line under the artist
        assertFalse(a.json().getJSONObject("assets").has("large_text"))
        // drift is not a change, a seek is
        assertTrue(a.sameAs(DiscordActivity.of(playing, 11_000, 100_500)))
        assertFalse(a.sameAs(DiscordActivity.of(playing, 60_000, 100_000)))
    }

    @Test fun talksToDiscordOverItsSocket() {
        assumeFalse(Platform.isWindows)
        val dir = Files.createTempDirectory("dc").toFile()
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(dir.resolve("discord-ipc-0").path))
        val got = mutableListOf<Pair<Int, JSONObject>>()
        val discord = thread {
            server.accept().use { c ->
                val input = DataInputStream(Channels.newInputStream(c))
                repeat(2) {
                    val head = ByteBuffer.wrap(ByteArray(8).also(input::readFully)).order(ByteOrder.LITTLE_ENDIAN)
                    val op = head.getInt()
                    got += op to JSONObject(String(ByteArray(head.getInt()).also(input::readFully)))
                    c.write(ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(2).put("{}".toByteArray()).flip() as ByteBuffer)
                }
            }
        }
        try {
            DiscordIpc.connect("123", listOf(dir))!!.use { it.show(DiscordActivity.of(playing, 0, 0)) }
            discord.join(5_000)
            assertEquals(0, got[0].first)
            assertEquals("123", got[0].second.getString("client_id"))
            assertEquals("Hujan Pagi", got[1].second.getJSONObject("args").getJSONObject("activity").getString("details"))
        } finally {
            server.close()
            dir.deleteRecursively()
        }
    }
}
