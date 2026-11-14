package com.spotifry.music.data.source

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val b = okhttp3.Request.Builder()
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            .url(request.url())
            .addHeader("User-Agent", USER_AGENT)
        request.headers().forEach { (k, values) ->
            b.removeHeader(k)
            values.forEach { b.addHeader(k, it) }
        }
        client.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("Rate limited", request.url())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string().orEmpty(), r.request.url.toString())
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
