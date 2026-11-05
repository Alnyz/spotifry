package com.spotifry.music.player

import android.content.SharedPreferences
import com.spotifry.music.data.source.Song
import com.spotifry.music.data.source.isLocal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.security.MessageDigest
import kotlin.math.abs

/** A part of a video marked as not music, in ms; [videoMs] is the video's length when it was marked, 0 if not known. */
data class Segment(val startMs: Long, val endMs: Long, val videoMs: Long = 0)

/** Times on the list are a little loose, and a song's length is in whole seconds. */
private const val SLACK_MS = 2_000L

/**
 * The trim that skips talking at the start and end of a song [lengthMs] long, or null when there is nothing worth
 * skipping. Only parts touching the start or the end count, and at least [Trim.MIN_KEPT_MS] must be left.
 */
// ponytail: parts in the middle are ignored, as a trim has one start and one end; skipping them needs a jump while playing
fun smartTrimOf(segments: List<Segment>, lengthMs: Long): Trim? {
    if (lengthMs <= 0) return null
    // marked on another cut of the video: its times would be off for this one
    val fit = segments.filter { it.videoMs <= 0 || abs(it.videoMs - lengthMs) <= SLACK_MS }
    val start = fit.filter { it.startMs <= SLACK_MS }.maxOfOrNull { it.endMs } ?: 0
    val end = fit.filter { it.endMs >= lengthMs - SLACK_MS }.minOfOrNull { it.startMs }?.takeIf { it < lengthMs - SLACK_MS }
    return Trim(start, end).takeUnless { it.isWhole || it.keptMs(lengthMs) < Trim.MIN_KEPT_MS }
}

/** The trim a song plays with: the user's own always wins over the smart one. */
fun trimFor(manual: Trim?, smart: Trim?, smartOn: Boolean): Trim? = manual ?: smart.takeIf { smartOn }

/** The "Non-music section" parts of [videoId] in SponsorBlock's answer, which may hold other videos with the same hash start. */
fun parseSegments(json: String, videoId: String): List<Segment> {
    val all = JSONArray(json)
    val video = (0 until all.length()).map(all::getJSONObject).firstOrNull { it.optString("videoID") == videoId } ?: return emptyList()
    val list = video.optJSONArray("segments") ?: return emptyList()
    return (0 until list.length()).map(list::getJSONObject)
        .filter { it.optString("category") == CATEGORY && it.optString("actionType", "skip") == "skip" }
        .map { o ->
            val at = o.getJSONArray("segment")
            Segment((at.getDouble(0) * 1000).toLong(), (at.getDouble(1) * 1000).toLong(), (o.optDouble("videoDuration", 0.0) * 1000).toLong())
        }
}

private const val CATEGORY = "music_offtopic"

/**
 * Smart trims, from SponsorBlock (sponsor.ajay.app), a free public list where YouTube viewers mark the parts of videos
 * that aren't music. Asked once per song, and the answer is kept on this phone: a trim, "" when there is nothing to
 * skip, or "off" when the user chose to hear the song whole. Replays and offline play use no data.
 */
// ponytail: "nothing to skip" is kept for good; ask again after a month or so if songs get marked later
class SmartTrims(private val prefs: SharedPreferences, private val client: OkHttpClient) {
    private val trims = Trims(prefs)

    /** The smart trim kept for song [id], null when none, off or not asked yet. */
    operator fun get(id: String): Trim? = trims[id]

    /** The user wants song [id] whole: it never gets a smart trim again. */
    fun turnOff(id: String) = prefs.edit().putString(id, OFF).apply()

    /** Asks the list about [song] unless already asked. Offline or any failure: nothing kept, so the next play asks again. */
    suspend fun lookUp(song: Song) {
        if (song.isLocal || song.durationSec <= 0 || prefs.contains(song.id)) return
        withContext(Dispatchers.IO) {
            // only the start of the id's hash goes out, so the list can't tell which song is playing
            val hash = MessageDigest.getInstance("SHA-256").digest(song.id.toByteArray()).joinToString("") { "%02x".format(it) }
            val url = "https://sponsor.ajay.app/api/skipSegments/${hash.take(4)}".toHttpUrl().newBuilder()
                .addQueryParameter("categories", "[\"$CATEGORY\"]").build()
            val segments = runCatching {
                client.newCall(Request.Builder().url(url).header("User-Agent", "Spotifry (personal music player)").build()).execute().use {
                    when {
                        it.code == 404 -> emptyList() // nothing marked for any video with this hash start
                        it.isSuccessful -> parseSegments(it.body!!.string(), song.id)
                        else -> null
                    }
                }
            }.getOrNull() ?: return@withContext
            val trim = smartTrimOf(segments, song.durationSec * 1000)
            // "Play the whole song" while asking: that choice stays
            if (prefs.contains(song.id)) return@withContext
            if (trim != null) trims[song.id] = trim else prefs.edit().putString(song.id, "").apply()
        }
    }

    private companion object {
        const val OFF = "off"
    }
}
