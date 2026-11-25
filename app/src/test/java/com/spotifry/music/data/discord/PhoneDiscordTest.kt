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
        val card = DiscordActivity.of("Song", "Band", "https://x/y.jpg", 1_000, 60_000, 100_000).gatewayJson("mp:a", "mp:b")
        assertEquals(2, card.getInt("type"))
        assertEquals("Get Spotifry", card.getJSONArray("buttons").getString(0))
        assertEquals("mp:b", card.getJSONObject("assets").getString("small_image"))
    }
}
