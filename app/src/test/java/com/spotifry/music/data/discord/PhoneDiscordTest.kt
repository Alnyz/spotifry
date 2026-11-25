package com.spotifry.music.data.discord

import android.net.Uri
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneDiscordTest {
    @Test fun theSignInAsksDiscordTheSafeWay() {
        // the example from the PKCE standard (RFC 7636)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", PhoneDiscord.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val dir = Files.createTempDirectory("dc").toFile()
        val discord = PhoneDiscord(OkHttpClient(), dir)
        val page = discord.signInPage()
        assertEquals("S256", page.getQueryParameter("code_challenge_method"))
        assertEquals("discord-$DISCORD_APP_ID:/authorize/callback", page.getQueryParameter("redirect_uri"))
        // an answer that isn't to this sign-in is ignored, and the real one can still arrive
        discord.finish(Uri.parse("discord-$DISCORD_APP_ID:/authorize/callback?code=x&state=someone-elses"))
        assertEquals(true, File(dir, "discord.json").readText().contains(page.getQueryParameter("state")!!))
        assertFalse(discord.connected.value)
    }

    @Test fun thePhonesCardCarriesTheButton() {
        val card = DiscordActivity.of("dQw4w9WgXcQ", "Song", "Band", "https://x/y.jpg", 1_000, 60_000, 100_000).gatewayJson("mp:a", "mp:b")
        assertEquals(2, card.getInt("type"))
        assertEquals("Listen to this song", card.getJSONArray("buttons").getString(0))
        assertEquals("https://youtu.be/dQw4w9WgXcQ", card.getJSONObject("metadata").getJSONArray("button_urls").getString(0))
        assertEquals("Get Spotifry", card.getJSONArray("buttons").getString(1))
        assertEquals(1, card.getInt("status_display_type"))
        // a file of the user's own has nothing to link to
        assertEquals(1, DiscordActivity.of("local:12", "Song", "Band", null, 0, 0, 0).gatewayJson(null, null).getJSONArray("buttons").length())
        assertEquals("mp:b", card.getJSONObject("assets").getString("small_image"))
    }
}
