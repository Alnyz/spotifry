package com.spotifry.music.data.discord

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import com.spotifry.music.data.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

private const val API = "https://discord.com/api/v10"
private const val REDIRECT = "discord-$DISCORD_APP_ID:/authorize/callback"
/** Discord's own set for apps that show what the user is doing. */
private const val SCOPES = "openid identify sdk.social_layer_presence"
private const val TAG = "discord"

/**
 * The playing song on the user's Discord profile, from the phone. The user approves Spotifry on Discord's own page
 * (OAuth with PKCE, so Spotifry never sees the password and ships no secret); the phone then holds its own connection
 * to Discord while a song plays, the way Discord's Social SDK does. Discord does not document this for apps without
 * that SDK, so it can stop working.
 *
 * The sign-in is kept in noBackupFilesDir: it must not travel in a phone backup.
 */
class PhoneDiscord(private val client: OkHttpClient, dir: File) {
    private val file = File(dir, "discord.json")
    private var saved = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    private val _connected = MutableStateFlow(saved.has("access"))
    val connected: StateFlow<Boolean> = _connected

    private var gateway: Gateway? = null
    private val pictures = HashMap<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized private fun keep(change: JSONObject.() -> Unit) {
        saved.change()
        runCatching { file.writeText(saved.toString()) }
        _connected.value = saved.has("access")
    }

    /** The page to open in the browser; the answer comes back through [DiscordRedirectActivity]. */
    fun signInPage(): Uri {
        val state = randomCode()
        val verifier = randomCode()
        keep { put("state", state); put("verifier", verifier) }
        return Uri.parse("https://discord.com/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", DISCORD_APP_ID).appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT).appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state).appendQueryParameter("code_challenge", challengeOf(verifier))
            .appendQueryParameter("code_challenge_method", "S256").build()
    }

    /** Only the answer to the sign-in this app started counts: any app or page can open the callback link. */
    fun finish(answer: Uri) {
        val state = saved.optString("state")
        val verifier = saved.optString("verifier")
        if (state.isEmpty() || answer.getQueryParameter("state") != state) return Log.sink(TAG, "sign-in answer ignored: not for the sign-in in progress")
        keep { remove("state"); remove("verifier") }
        val code = answer.getQueryParameter("code")
            ?: return Log.sink(TAG, "sign-in refused: ${answer.getQueryParameter("error")} ${answer.getQueryParameter("error_description").orEmpty()}")
        scope.launch {
            token(FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("redirect_uri", REDIRECT).add("code_verifier", verifier))
        }
    }

    /** Forgets the sign-in here and asks Discord to forget it too. */
    fun disconnect() {
        val refresh = saved.optString("refresh")
        keep { remove("access"); remove("refresh"); remove("expires") }
        if (refresh.isEmpty()) return
        scope.launch {
            runCatching {
                val body = FormBody.Builder().add("client_id", DISCORD_APP_ID).add("token", refresh).add("token_type_hint", "refresh_token").build()
                client.newCall(Request.Builder().url("$API/oauth2/token/revoke").post(body).build()).execute().close()
            }
        }
    }

    private fun token(form: FormBody.Builder) {
        runCatching {
            val request = Request.Builder().url("$API/oauth2/token").post(form.add("client_id", DISCORD_APP_ID).build()).build()
            client.newCall(request).execute().use { r ->
                if (r.isSuccessful) {
                    val o = JSONObject(r.body!!.string())
                    keep {
                        put("access", o.getString("access_token"))
                        o.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { put("refresh", it) }
                        put("expires", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000)
                    }
                } else {
                    Log.sink(TAG, "sign-in refused: HTTP ${r.code}")
                    // the user took Spotifry's permission away on Discord
                    if (r.code == 400 || r.code == 401) keep { remove("access"); remove("refresh"); remove("expires") }
                }
            }
        }.onFailure { Log.sink(TAG, "sign-in: ${it.message}") }
    }

    private fun accessToken(): String? {
        if (saved.has("access") && System.currentTimeMillis() > saved.optLong("expires") - 600_000) {
            token(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", saved.optString("refresh")))
        }
        return saved.optString("access").takeIf { it.isNotEmpty() }
    }

    /** Discord only shows web pictures it has been given first; null when it won't take this one. */
    private fun picture(url: String, token: String): String? = pictures[url] ?: runCatching {
        val body = JSONObject().put("urls", JSONArray().put(url)).toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$API/applications/$DISCORD_APP_ID/external-assets").header("Authorization", "Bearer $token").post(body).build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) { Log.sink(TAG, "picture refused: HTTP ${r.code} ${r.body?.string()?.take(200)}"); null } else "mp:" + JSONArray(r.body!!.string()).getJSONObject(0).getString("external_asset_path")
        }
    }.getOrNull()?.also { pictures[url] = it }

    private suspend fun show(activity: DiscordActivity): Boolean = withContext(Dispatchers.IO) {
        val token = accessToken() ?: return@withContext false
        val link = gateway?.takeIf { it.open } ?: Gateway(client, token).let { fresh -> if (fresh.connect()) fresh.also { gateway = it } else null }
            ?: return@withContext false
        val large = picture(activity.cover ?: DISCORD_LOGO, token)
        val small = if (activity.cover != null) picture(DISCORD_LOGO, token) else null
        link.presence(activity.gatewayJson(large, small))
    }

    private fun hide() {
        val link = gateway ?: return
        gateway = null
        // cleared first: closing alone can leave the old song up for a minute
        link.presence(null)
        link.close()
    }

    /**
     * Keeps the profile in step with [now] (null: show nothing) until cancelled. Looks every two seconds, which also
     * stays within Discord's limit on changes. The connection is open only while something shows.
     */
    suspend fun follow(now: () -> DiscordActivity?) {
        var shown: DiscordActivity? = null
        var wait = 0
        try {
            while (true) {
                val want = if (connected.value) now() else null
                if (want == null) { hide(); shown = null }
                else if (wait > 0) wait--
                else if (!want.sameAs(shown) || gateway?.open != true) {
                    // no connection: try again in half a minute, not every two seconds
                    if (show(want)) shown = want else { shown = null; wait = 15 }
                }
                delay(2_000)
            }
        } finally {
            hide()
        }
    }

    /** One connection to Discord: it says hello, we sign in and then beat to keep it open. */
    private class Gateway(private val client: OkHttpClient, private val token: String) : WebSocketListener() {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val ready = CompletableDeferred<Boolean>()
        private var socket: WebSocket? = null
        private var beat: Job? = null
        @Volatile private var sequence: Long? = null
        @Volatile private var answered = true
        @Volatile var open = false
            private set

        suspend fun connect(): Boolean {
            socket = client.newWebSocket(Request.Builder().url("wss://gateway.discord.gg/?v=10&encoding=json").build(), this)
            val ok = withTimeoutOrNull(20_000) { ready.await() } == true
            if (!ok) close()
            return ok
        }

        private fun send(op: Int, data: Any?) = socket?.send(JSONObject().put("op", op).put("d", data ?: JSONObject.NULL).toString()) == true

        fun presence(activity: JSONObject?): Boolean = open && send(
            3, JSONObject().put("since", JSONObject.NULL).put("status", "online").put("afk", false)
                .put("activities", JSONArray().apply { activity?.let { put(it) } }),
        )

        fun close() {
            open = false
            ready.complete(false)
            beat?.cancel()
            socket?.close(1000, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = runCatching { JSONObject(text) }.getOrNull() ?: return
            if (!m.isNull("s")) sequence = m.optLong("s")
            when (m.optInt("op", -1)) {
                10 -> {
                    val every = m.optJSONObject("d")?.optLong("heartbeat_interval", 41_250) ?: 41_250
                    beat = scope.launch {
                        delay((every * Math.random()).toLong())
                        while (answered) { answered = false; send(1, sequence); delay(every) }
                        close()
                    }
                    // the same capabilities and intents Discord's Social SDK asks for, less friends' presence
                    send(2, JSONObject().put("token", "Bearer $token").put("capabilities", 69680).put("intents", 944508928)
                        .put("properties", JSONObject().put("os", "Android").put("browser", "Spotifry").put("device", "Android")))
                }
                11 -> answered = true
                1 -> send(1, sequence)
                7, 9 -> close()
                0 -> if (m.optString("t") == "READY") { open = true; ready.complete(true) }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (open) Log.sink(TAG, "closed by Discord: $code $reason")
            close()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.sink(TAG, "connection: ${t.message}")
            close()
        }
    }

    companion object {
        private fun urlSafe(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        private fun randomCode(): String = urlSafe(ByteArray(48).also(SecureRandom()::nextBytes))

        fun challengeOf(verifier: String): String = urlSafe(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}

/** Takes Discord's answer from the browser, hands it on and closes; it shows nothing. */
class DiscordRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.takeIf { it.isHierarchical }?.let { (application as com.spotifry.music.MusicApp).container.discord.finish(it) }
        // back to Spotifry rather than leaving the browser in front
        packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        finish()
    }
}
