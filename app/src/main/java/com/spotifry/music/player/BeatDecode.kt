// The decode loop and reading through the player's own data source are adapted from Mochy Music (GPL-3.0), see licenses/Mochy-Music-NOTICE.txt.

package com.spotifry.music.player

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** Decoded sound, [count] samples of [mono] from [startMs] of the song. [reachedEnd] is false when the read stopped short. */
class Stretch(val mono: FloatArray, val count: Int, val startMs: Long, val reachedEnd: Boolean)

/**
 * The stretch of a song from [fromMs] to [toMs] as mono at [ANALYSIS_RATE], with where it really starts. Goes through the
 * player's own [factory], so what's cached or saved isn't fetched again and only this stretch is read from the network.
 */
@UnstableApi
suspend fun decodeStretch(factory: DataSource.Factory, uri: Uri, id: String, fromMs: Long, toMs: Long): Stretch? {
    val job = coroutineContext[kotlinx.coroutines.Job]
    val source = SongBytes(factory, uri, id) { job?.isActive != false }
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(source)
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
        val format = extractor.getTrackFormat(track)
        extractor.selectTrack(track)
        if (fromMs > 0) extractor.seekTo(fromMs * 1_000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val out = FloatArray(((toMs - fromMs) * ANALYSIS_RATE / 1_000).toInt() + ANALYSIS_RATE)
        var n = 0
        var startMs = -1L
        var lastMs = -1L
        var pos = 0.0; var prev = 0f
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        while (true) {
            coroutineContext.ensureActive()
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val size = codec.getInputBuffer(i)?.let { extractor.readSampleData(it, 0) } ?: -1
                    if (size < 0 || extractor.sampleTime > toMs * 1_000) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT); rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT) return null
            } else if (o >= 0) {
                val b = codec.getOutputBuffer(o)
                if (b != null && info.size > 0 && info.presentationTimeUs >= fromMs * 1_000) {
                    if (startMs < 0) startMs = info.presentationTimeUs / 1_000
                    lastMs = info.presentationTimeUs / 1_000
                    b.position(info.offset); b.limit(info.offset + info.size)
                    val pcm = b.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val step = rate.toDouble() / ANALYSIS_RATE
                    repeat(pcm.remaining() / channels) {
                        var sum = 0f
                        repeat(channels) { sum += pcm.get() / 32_768f }
                        val now = sum / channels
                        while (pos <= 1.0) { if (n < out.size) out[n++] = prev + (now - prev) * pos.toFloat(); pos += step }
                        pos -= 1.0; prev = now
                    }
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        return if (startMs < 0) null else Stretch(out, n, startMs, lastMs >= toMs - REACHED_WITHIN_MS)
    } finally {
        codec?.runCatching { stop() }
        codec?.runCatching { release() }
        runCatching { extractor.release() }
        runCatching { source.close() }
    }
}

private const val SKIP = 1_000_000L
/** A song's last samples can end this far before its stated length without the read having stopped short. */
private const val REACHED_WITHIN_MS = 2_000L
/** About a minute and a half at high quality: more than a stretch of 30 s should cost. */
private const val MAX_FETCH = 2_000_000L

/**
 * The song's bytes for [MediaExtractor]. The resolver can hand a fresh stream back one chunk at a time, so the end of an
 * open isn't the end of the song: reading carries on until the song itself has no more.
 */
@UnstableApi
private class SongBytes(private val factory: DataSource.Factory, private val uri: Uri, private val id: String, private val wanted: () -> Boolean) : MediaDataSource() {
    private var source: DataSource? = null
    private var at = 0L
    private var length = -1L
    private var fromNetwork = false
    private var fetched = 0L
    private val skipped = ByteArray(16_384)

    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, size: Int): Int {
        if (size == 0) return 0
        // gives up rather than block: analysis was cancelled, or would fetch more than it's worth
        if ((length >= 0 && offset >= length) || !wanted() || fetched > MAX_FETCH) return -1
        if (source == null || offset < at || offset - at > SKIP) open(offset)
        // the extractor hops forward a little at a time: reading past is far cheaper than a new connection each hop
        while (at < offset) {
            val read = source!!.read(skipped, 0, minOf(skipped.size.toLong(), offset - at).toInt())
            if (read == C.RESULT_END_OF_INPUT) { open(offset); break }
            at += read; if (fromNetwork) fetched += read
        }
        var total = 0; var again = false
        while (total < size) {
            val read = source!!.read(buffer, bufferOffset + total, size - total)
            if (read == C.RESULT_END_OF_INPUT) {
                if (again || (length >= 0 && at >= length)) break
                open(at); again = true
                continue
            }
            again = false; total += read; at += read
            if (fromNetwork) fetched += read
        }
        return if (total == 0) -1 else total
    }

    override fun getSize(): Long {
        if (length < 0 && source == null) runCatching { open(0) }
        return length
    }

    override fun close() { runCatching { source?.close() }; source = null }

    private fun open(offset: Long) {
        close()
        val next = factory.createDataSource()
        // not closed, a failed open would hold its lock on the song's cache until the app restarts
        val opened = try { next.open(DataSpec.Builder().setUri(uri).setKey(id).setPosition(offset).build()) } catch (e: Exception) { runCatching { next.close() }; throw e }
        val range = next.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
        range?.substringAfterLast('/')?.trim()?.toLongOrNull()?.let { length = it }
        // nothing came from the network: the cache knows how much there is
        if (length < 0 && next.responseHeaders.isEmpty() && opened != C.LENGTH_UNSET.toLong()) length = offset + opened
        fromNetwork = next.responseHeaders.isNotEmpty()
        source = next; at = offset
    }
}
