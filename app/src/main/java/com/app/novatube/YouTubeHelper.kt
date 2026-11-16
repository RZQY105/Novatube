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
    private const val VR_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    private const val IOS_USER_AGENT =
        "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"
    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    @Volatile
    var activeUserAgent: String = VR_USER_AGENT

    @Volatile
    private var cachedVisitorData: String = ""

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

    private fun getVisitorData(): String {
        if (cachedVisitorData.isNotBlank()) return cachedVisitorData
        return try {
            val payload = """
                {
                  "context": {
                    "client": {
                      "clientName": "ANDROID_VR",
                      "clientVersion": "1.60.19"
                    }
                  }
                }
            """.trimIndent()

            val req = okhttp3.Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", VR_USER_AGENT)
                .header("X-YouTube-Client-Name", "28")
                .header("X-YouTube-Client-Version", "1.60.19")
                .build()

            val body = httpClient.newCall(req).execute().body?.string() ?: return ""
            val visitor = JSONObject(body)
                .optJSONObject("responseContext")
                ?.optString("visitorData", "")
                .orEmpty()

            if (visitor.isNotBlank()) {
                cachedVisitorData = visitor
            }
            visitor
        } catch (_: Exception) {
            ""
        }
    }

    suspend fun extractStreams(youtubeUrl: String): MediaEntry = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(youtubeUrl)
            ?: throw IllegalArgumentException("Link YouTube tidak valid")
        val visitorData = getVisitorData()
        val errors = mutableListOf<String>()

        // 1. Klien ANDROID_VR (mengembalikan URL stream langsung tanpa PoToken / enkripsi tanda tangan)
        fetchInnertubePlayer(
            videoId = videoId,
            pageUrl = youtubeUrl.trim(),
            clientIdHeader = "28",
            clientName = "ANDROID_VR",
            clientVersion = "1.60.19",
            userAgent = VR_USER_AGENT,
            visitorData = visitorData,
            extraContext = """
                "deviceMake": "Oculus",
                "deviceModel": "Quest 3",
                "androidSdkVersion": 32,
                "osName": "Android",
                "osVersion": "12L",
            """.trimIndent(),
            onError = { errors.add("VR: $it") }
        )?.let {
            activeUserAgent = VR_USER_AGENT
            return@withContext it
        }

        // 2. Klien IOS dengan header lengkap & visitorData
        fetchInnertubePlayer(
            videoId = videoId,
            pageUrl = youtubeUrl.trim(),
            clientIdHeader = "5",
            clientName = "IOS",
            clientVersion = "19.45.4",
            userAgent = IOS_USER_AGENT,
            visitorData = visitorData,
            extraContext = """
                "deviceMake": "Apple",
                "deviceModel": "iPhone16,2",
                "osName": "iPhone",
                "osVersion": "18.1.0.22B83",
            """.trimIndent(),
            onError = { errors.add("iOS: $it") }
        )?.let {
            activeUserAgent = IOS_USER_AGENT
            return@withContext it
        }

        // 3. Klien TVHTML5
        fetchInnertubePlayer(
            videoId = videoId,
            pageUrl = youtubeUrl.trim(),
            clientIdHeader = "7",
            clientName = "TVHTML5",
            clientVersion = "7.20250312.16.00",
            userAgent = WEB_USER_AGENT,
            visitorData = visitorData,
            extraContext = "",
            onError = { errors.add("TV: $it") }
        )?.let {
            activeUserAgent = WEB_USER_AGENT
            return@withContext it
        }

        // 4. Cadangan via Server Piped API publik
        fetchFromPiped(videoId, youtubeUrl.trim())?.let {
            activeUserAgent = WEB_USER_AGENT
            return@withContext it
        }

        throw IllegalStateException(errors.firstOrNull() ?: "Stream tidak tersedia dari server")
    }

    private fun fetchInnertubePlayer(
        videoId: String,
        pageUrl: String,
        clientIdHeader: String,
        clientName: String,
        clientVersion: String,
        userAgent: String,
        visitorData: String,
        extraContext: String,
        onError: (String) -> Unit
    ): MediaEntry? {
        return try {
            val visitorField = if (visitorData.isNotBlank()) {
                "\"visitorData\": \"$visitorData\","
            } else {
                ""
            }

            val payload = """
                {
                  "context": {
                    "client": {
                      $extraContext
                      $visitorField
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

            val reqBuilder = okhttp3.Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", userAgent)
                .header("X-YouTube-Client-Name", clientIdHeader)
                .header("X-YouTube-Client-Version", clientVersion)
                .header("Origin", "https://www.youtube.com")

            if (visitorData.isNotBlank()) {
                reqBuilder.header("X-Goog-Visitor-Id", visitorData)
            }

            val response = httpClient.newCall(reqBuilder.build()).execute()
            val bodyStr = response.body?.string() ?: run {
                onError("HTTP ${response.code}")
                return null
            }
            val json = JSONObject(bodyStr)

            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status").orEmpty()
            if (status != "OK") {
                val reason = playability?.optString("reason").orEmpty().ifBlank { status }
                onError(reason)
                return null
            }

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

            val streamingData = json.optJSONObject("streamingData") ?: run {
                onError("Tidak ada streamingData")
                return null
            }
            val formats = streamingData.optJSONArray("formats")
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            val hlsUrl = streamingData.optString("hlsManifestUrl", "")

            // Stream MP4 gabungan video + audio
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

            // Stream audio murni (M4A / WebM)
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
            if (finalVideo.isBlank()) {
                onError("URL stream kosong")
                return null
            }

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
        } catch (e: Exception) {
            onError(e.message ?: "Exception")
            null
        }
    }

    private fun fetchFromPiped(videoId: String, pageUrl: String): MediaEntry? {
        val instances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://api.piped.private.coffee"
        )
        for (base in instances) {
            try {
                val req = okhttp3.Request.Builder()
                    .url("$base/streams/$videoId")
                    .header("User-Agent", WEB_USER_AGENT)
                    .build()
                val res = httpClient.newCall(req).execute()
                if (!res.isSuccessful) continue
                val json = JSONObject(res.body?.string() ?: continue)

                val title = json.optString("title", "Video YouTube")
                val author = json.optString("uploader", "YouTube")
                val duration = json.optLong("duration", 0L)
                val thumb = json.optString("thumbnailUrl", "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")

                var videoUrl = ""
                val videoStreams = json.optJSONArray("videoStreams")
                if (videoStreams != null) {
                    for (i in 0 until videoStreams.length()) {
                        val s = videoStreams.getJSONObject(i)
                        if (!s.optBoolean("videoOnly", true)) {
                            val u = s.optString("url", "")
                            if (u.isNotBlank()) {
                                videoUrl = u
                                break
                            }
                        }
                    }
                }

                var audioUrl = ""
                val audioStreams = json.optJSONArray("audioStreams")
                if (audioStreams != null && audioStreams.length() > 0) {
                    audioUrl = audioStreams.getJSONObject(0).optString("url", "")
                }

                val finalVid = videoUrl.ifBlank { audioUrl }
                val finalAud = audioUrl.ifBlank { finalVid }
                if (finalVid.isNotBlank()) {
                    return MediaEntry(
                        id = videoId,
                        title = title,
                        author = author,
                        duration = formatDuration(duration),
                        thumbnailUrl = thumb,
                        pageUrl = pageUrl,
                        videoStreamUrl = finalVid,
                        audioStreamUrl = finalAud
                    )
                }
            } catch (_: Exception) {
                // Coba instance berikutnya
            }
        }
        return null
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
