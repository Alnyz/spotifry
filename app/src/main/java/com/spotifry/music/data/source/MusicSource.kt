package com.spotifry.music.data.source

data class ResolvedStream(val url: String, val mimeType: String, val bitrateKbps: Int, val expiresAtMs: Long)

interface MusicSource {
    suspend fun search(query: String): List<Song>
    /** [low]: the smallest audio stream instead of the best one (data saver, low-quality saves). */
    suspend fun resolve(song: Song, low: Boolean = false): ResolvedStream
    suspend fun related(song: Song): List<Song>
}
