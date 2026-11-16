package com.app.novatube

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
    const val STREAM_USER_AGENT =
        "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"

    private var initialized = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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

    fun extractVideoId(url: String): String? {
        return try {
            val uri = Uri.parse(url.trim())
            val host = uri.host?.lowercase() ?: return null
            when {
                host.contains("youtu.be") -> uri.pathSegments.firstOrNull()
                host.contains("youtube.com") -> {
                    uri.getQueryParameter("v") ?: run {
                        val segments = uri.pathSegments
                        if (segments.size >= 2 && segments[0] in listOf("shorts", "embed", "live", "v")) {
                            segments[1]
                        } else null
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun extractStreams(youtubeUrl: String): MediaEntry = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(youtubeUrl)
            ?: throw IllegalArgumentException("Link YouTube tidak valid")

        // 1. Coba via Innertube iOS Client (mengembalikan URL MP4/M4A langsung tanpa reload halaman)
        fetchInnertubePlayer(
            videoId = videoId,
            pageUrl = youtubeUrl.trim(),
            clientName = "IOS",
            clientVersion = "19.45.4",
            userAgent = STREAM_USER_AGENT,
            extraContext = """
                "deviceMake": "Apple",
                "deviceModel": "iPhone16,2",
                "osName": "iPhone",
                "osVersion": "18.1.0.22B83",
            """.trimIndent()
        )?.let { return@withContext it }

        // 2. Cadangan via Innertube Android Client
        fetchInnertubePlayer(
            videoId = videoId,
            pageUrl = youtubeUrl.trim(),
            clientName = "ANDROID",
            clientVersion = "19.45.38",
            userAgent = "com.google.android.youtube/19.45.38 (Linux; U; Android 14) gzip",
            extraContext = """
                "androidSdkVersion": 34,
                "osName": "Android",
                "osVersion": "14",
            """.trimIndent()
        )?.let { return@withContext it }

        // 3. Cadangan terakhir via NewPipeExtractor
        ensureInit()
        val info = StreamInfo.getInfo(ServiceList.YouTube, youtubeUrl.trim())
        val bestVideo = info.videoStreams
            .filter { !it.isVideoOnly && it.content.isNotBlank() }
            .maxByOrNull { it.height }
            ?.content ?: ""
        val bestAudio = info.audioStreams
            .filter { it.content.isNotBlank() }
            .maxByOrNull { it.averageBitrate }
            ?.content ?: bestVideo

        MediaEntry(
            id = videoId,
            title = info.name ?: "Video YouTube",
            author = info.uploaderName ?: "YouTube",
            duration = formatDuration(info.duration),
            thumbnailUrl = info.thumbnails.lastOrNull()?.url ?: "",
            pageUrl = youtubeUrl.trim(),
            videoStreamUrl = bestVideo.ifBlank { bestAudio },
            audioStreamUrl = bestAudio
        )
    }

    private fun fetchInnertubePlayer(
        videoId: String,
        pageUrl: String,
        clientName: String,
        clientVersion: String,
        userAgent: String,
        extraContext: String
    ): MediaEntry? {
        return try {
            val payload = """
                {
                  "context": {
                    "client": {
                      $extraContext
                      "clientName": "$clientName",
                      "clientVersion": "$clientVersion",
                      "hl": "id",
                      "gl": "ID"
                    }
                  },
                  "videoId": "$videoId",
                  "contentCheckOk": true,
                  "racyCheckOk": true
                }
            """.trimIndent()

            val request = okhttp3.Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/json")
                .header("Origin", "https://www.youtube.com")
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyStr = response.body?.string() ?: return null
            val json = JSONObject(bodyStr)

            val status = json.optJSONObject("playabilityStatus")?.optString("status")
            if (status != "OK") return null

            val details = json.optJSONObject("videoDetails")
            val title = details?.optString("title").takeUnless { it.isNullOrBlank() } ?: "Video YouTube"
            val author = details?.optString("author").takeUnless { it.isNullOrBlank() } ?: "YouTube"
            val lengthSec = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
            val thumbs = details?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val thumbUrl = if (thumbs != null && thumbs.length() > 0) {
                thumbs.getJSONObject(thumbs.length() - 1).optString("url", "")
            } else {
                "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            }

            val streamingData = json.optJSONObject("streamingData") ?: return null
            val formats = streamingData.optJSONArray("formats")
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            val hlsUrl = streamingData.optString("hlsManifestUrl", "")

            var bestVideoUrl = ""
            var maxHeight = 0
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    val f = formats.getJSONObject(i)
                    val url = f.optString("url", "")
                    val height = f.optInt("height", 0)
                    if (url.isNotBlank() && height >= maxHeight) {
                        maxHeight = height
                        bestVideoUrl = url
                    }
                }
            }

            var bestAudioUrl = ""
            var maxBitrate = 0
            if (adaptiveFormats != null) {
                for (i in 0 until adaptiveFormats.length()) {
                    val f = adaptiveFormats.getJSONObject(i)
                    val mime = f.optString("mimeType", "")
                    val url = f.optString("url", "")
                    val bitrate = f.optInt("bitrate", 0)
                    if (mime.startsWith("audio/") && url.isNotBlank() && bitrate >= maxBitrate) {
                        maxBitrate = bitrate
                        bestAudioUrl = url
                    }
                }
            }

            val finalVideo = bestVideoUrl.ifBlank { hlsUrl }.ifBlank { bestAudioUrl }
            val finalAudio = bestAudioUrl.ifBlank { finalVideo }
            if (finalVideo.isBlank()) return null

            MediaEntry(
                id = videoId,
                title = title,
                author = author,
                duration = formatDuration(lengthSec),
                thumbnailUrl = thumbUrl,
                pageUrl = pageUrl,
                videoStreamUrl = finalVideo,
                audioStreamUrl = finalAudio
            )
        } catch (_: Exception) {
            null
        }
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

            val response = httpClient.newCall(reqBuilder.build()).execute()
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
