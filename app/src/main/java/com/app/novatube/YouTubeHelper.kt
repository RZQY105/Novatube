package com.app.novatube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.TimeUnit

data class MediaEntry(
    val id: String,
    val title: String,
    val author: String,
    val duration: String,
    val thumbnailUrl: String,
    val pageUrl: String,
    val videoStreamUrl: String = "",
    val audioStreamUrl: String = ""
)

object YouTubeHelper {
    private var initialized = false

    private fun ensureInit() {
        if (!initialized) {
            NewPipe.init(OkHttpDownloader())
            initialized = true
        }
    }

    fun isYouTubeUrl(input: String): Boolean {
        val text = input.trim().lowercase()
        return text.contains("youtube.com/") || text.contains("youtu.be/")
    }

    suspend fun extractStreams(youtubeUrl: String): MediaEntry = withContext(Dispatchers.IO) {
        ensureInit()
        val info = StreamInfo.getInfo(ServiceList.YouTube, youtubeUrl.trim())

        // Stream video MP4 yang sudah termasuk audio (720p/360p)
        val bestVideo = info.videoStreams
            .filter { !it.isVideoOnly && it.content.isNotBlank() }
            .maxByOrNull { it.height }
            ?.content ?: ""

        // Stream audio murni terbaik (M4A/WebM)
        val bestAudio = info.audioStreams
            .filter { it.content.isNotBlank() }
            .maxByOrNull { it.averageBitrate }
            ?.content ?: bestVideo

        val thumb = info.thumbnails.lastOrNull()?.url ?: ""

        MediaEntry(
            id = info.id ?: System.currentTimeMillis().toString(),
            title = info.name ?: "Video YouTube",
            author = info.uploaderName ?: "YouTube",
            duration = formatDuration(info.duration),
            thumbnailUrl = thumb,
            pageUrl = youtubeUrl.trim(),
            videoStreamUrl = bestVideo.ifBlank { bestAudio },
            audioStreamUrl = bestAudio
        )
    }

    suspend fun searchVideos(query: String): List<MediaEntry> = withContext(Dispatchers.IO) {
        ensureInit()
        val extractor = ServiceList.YouTube.getSearchExtractor(query.trim())
        extractor.fetchPage()

        extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .map { item ->
                MediaEntry(
                    id = item.url,
                    title = item.name ?: "Tanpa Judul",
                    author = item.uploaderName ?: "YouTube",
                    duration = formatDuration(item.duration),
                    thumbnailUrl = item.thumbnails.lastOrNull()?.url ?: "",
                    pageUrl = item.url
                )
            }
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "LIVE"
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) {
            String.format("%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format("%02d:%02d", mins, secs)
        }
    }

    private class OkHttpDownloader : Downloader() {
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody(null)
            val reqBuilder = okhttp3.Request.Builder()
                .method(request.httpMethod(), body)
                .url(request.url())
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
                )

            request.headers().forEach { (key, values) ->
                values.forEach { value ->
                    reqBuilder.addHeader(key, value)
                }
            }

            val response = client.newCall(reqBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""
            val headersMap = mutableMapOf<String, List<String>>()
            response.headers.names().forEach { name ->
                headersMap[name] = response.headers.values(name)
            }

            return Response(
                response.code,
                response.message,
                headersMap,
                responseBody,
                response.request.url.toString()
            )
        }
    }
}
