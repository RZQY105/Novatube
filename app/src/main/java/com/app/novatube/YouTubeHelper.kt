package com.app.novatube

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONTokener
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

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
    private const val TV_USER_AGENT =
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/4.0 Chrome/76.0.3809.146 TV Safari/537.36"
    private const val MOBILE_WEB_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    @Volatile
    var activeUserAgent: String = TV_USER_AGENT

    @Volatile
    var activeCookie: String = ""

    private var appContext: Context? = null
    private var initialized = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

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
        val cleanPageUrl = "https://www.youtube.com/watch?v=$videoId"

        // 1. Jalur Smart TV Embedded Player (umumnya bebas cek bot tanpa login)
        fetchEmbeddedPlayer(
            videoId = videoId,
            pageUrl = cleanPageUrl,
            clientIdHeader = "85",
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            userAgent = TV_USER_AGENT
        )?.let {
            activeUserAgent = TV_USER_AGENT
            activeCookie = ""
            return@withContext it
        }

        // 2. Jalur Web Embedded Player
        fetchEmbeddedPlayer(
            videoId = videoId,
            pageUrl = cleanPageUrl,
            clientIdHeader = "56",
            clientName = "WEB_EMBEDDED_PLAYER",
            clientVersion = "1.20250310.01.00",
            userAgent = MOBILE_WEB_USER_AGENT
        )?.let {
            activeUserAgent = MOBILE_WEB_USER_AGENT
            activeCookie = ""
            return@withContext it
        }

        // 3. Jalur Server Proxy Invidious & Piped (melewati blokir IP lokal)
        fetchFromProxyInstances(videoId, cleanPageUrl)?.let {
            activeUserAgent = MOBILE_WEB_USER_AGENT
            activeCookie = ""
            return@withContext it
        }

        // 4. Jalur Browser Chrome WebView Bawaan Android (menjalankan sesi browser asli di perangkat)
        appContext?.let { ctx ->
            extractViaWebView(ctx, videoId, cleanPageUrl)?.let {
                return@withContext it
            }
        }

        throw IllegalStateException("Video tidak dapat diputar. Coba video lain atau periksa koneksi.")
    }

    private fun fetchEmbeddedPlayer(
        videoId: String,
        pageUrl: String,
        clientIdHeader: String,
        clientName: String,
        clientVersion: String,
        userAgent: String
    ): MediaEntry? {
        return try {
            val payload = """
                {
                  "context": {
                    "client": {
                      "clientName": "$clientName",
                      "clientVersion": "$clientVersion",
                      "hl": "id",
                      "gl": "ID"
                    },
                    "thirdParty": {
                      "embedUrl": "https://www.youtube.com/"
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
                .header("X-YouTube-Client-Name", clientIdHeader)
                .header("X-YouTube-Client-Version", clientVersion)
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyStr = response.body?.string() ?: return null
            parsePlayerResponse(JSONObject(bodyStr), videoId, pageUrl)
        } catch (_: Exception) {
            null
        }
    }

    private fun parsePlayerResponse(
        json: JSONObject,
        videoId: String,
        pageUrl: String
    ): MediaEntry? {
        val status = json.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
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

        return MediaEntry(
            id = videoId,
            title = title,
            author = author,
            duration = formatDuration(lengthSec),
            thumbnailUrl = thumbUrl,
            pageUrl = pageUrl,
            videoStreamUrl = finalVideo,
            audioStreamUrl = finalAudio
        )
    }

    private fun fetchFromProxyInstances(videoId: String, pageUrl: String): MediaEntry? {
        val invidiousHosts = listOf(
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
            "https://invidious.protokolla.fi",
            "https://iv.datura.network"
        )
        for (host in invidiousHosts) {
            try {
                val req = okhttp3.Request.Builder()
                    .url("$host/api/v1/videos/$videoId")
                    .header("User-Agent", MOBILE_WEB_USER_AGENT)
                    .build()
                val res = httpClient.newCall(req).execute()
                if (!res.isSuccessful) continue
                val json = JSONObject(res.body?.string() ?: continue)

                val title = json.optString("title", "Video YouTube")
                val author = json.optString("author", "YouTube")
                val lengthSec = json.optLong("lengthSeconds", 0L)

                var videoUrl = ""
                val formatStreams = json.optJSONArray("formatStreams")
                if (formatStreams != null) {
                    for (i in 0 until formatStreams.length()) {
                        val s = formatStreams.getJSONObject(i)
                        val u = s.optString("url", "")
                        if (u.isNotBlank()) {
                            videoUrl = if (u.startsWith("/")) "$host$u" else u
                        }
                    }
                }

                var audioUrl = ""
                val adaptive = json.optJSONArray("adaptiveFormats")
                if (adaptive != null) {
                    for (i in 0 until adaptive.length()) {
                        val s = adaptive.getJSONObject(i)
                        val type = s.optString("type", "")
                        val u = s.optString("url", "")
                        if (type.startsWith("audio/") && u.isNotBlank()) {
                            audioUrl = if (u.startsWith("/")) "$host$u" else u
                            break
                        }
                    }
                }

                val finalVid = videoUrl.ifBlank { audioUrl }
                val finalAud = audioUrl.ifBlank { finalVid }
                if (finalVid.isNotBlank()) {
                    return MediaEntry(
                        id = videoId,
                        title = title,
                        author = author,
                        duration = formatDuration(lengthSec),
                        thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                        pageUrl = pageUrl,
                        videoStreamUrl = finalVid,
                        audioStreamUrl = finalAud
                    )
                }
            } catch (_: Exception) {
            }
        }

        val pipedHosts = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de",
            "https://api.piped.private.coffee"
        )
        for (host in pipedHosts) {
            try {
                val req = okhttp3.Request.Builder()
                    .url("$host/streams/$videoId")
                    .header("User-Agent", MOBILE_WEB_USER_AGENT)
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
            }
        }
        return null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractViaWebView(
        context: Context,
        videoId: String,
        pageUrl: String
    ): MediaEntry? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(14_000L) {
            suspendCancellableCoroutine { continuation ->
                var finished = false
                var interceptedVideo = ""
                var interceptedAudio = ""

                val webView = WebView(context)
                val ua = webView.settings.userAgentString
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.mediaPlaybackRequiresUserGesture = false

                fun completeWith(result: MediaEntry?) {
                    if (!finished) {
                        finished = true
                        if (result != null) {
                            activeUserAgent = ua
                            activeCookie = runCatching {
                                CookieManager.getInstance().getCookie("https://m.youtube.com").orEmpty()
                            }.getOrDefault("")
                        }
                        runCatching {
                            webView.stopLoading()
                            webView.destroy()
                        }
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString().orEmpty()
                        if (reqUrl.contains("googlevideo.com/videoplayback") && !reqUrl.contains("sabr=1")) {
                            val cleanUrl = cleanGoogleVideoUrl(reqUrl)
                            if (reqUrl.contains("mime=audio")) {
                                if (interceptedAudio.isBlank()) interceptedAudio = cleanUrl
                            } else if (reqUrl.contains("mime=video")) {
                                if (interceptedVideo.isBlank()) interceptedVideo = cleanUrl
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val script = """
                            (function() {
                                try {
                                    var r = window.ytInitialPlayerResponse;
                                    if (!r && window.ytplayer && window.ytplayer.config && window.ytplayer.config.args) {
                                        r = JSON.parse(window.ytplayer.config.args.raw_player_response);
                                    }
                                    return r ? JSON.stringify(r) : null;
                                } catch (e) {
                                    return null;
                                }
                            })();
                        """.trimIndent()

                        view?.evaluateJavascript(script) { raw ->
                            val parsed = runCatching {
                                val unwrapped = JSONTokener(raw).nextValue()
                                if (unwrapped is String && unwrapped.startsWith("{")) {
                                    parsePlayerResponse(JSONObject(unwrapped), videoId, pageUrl)
                                } else null
                            }.getOrNull()

                            if (parsed != null) {
                                completeWith(parsed)
                            } else {
                                // Tunggu sebentar jika pemutar mulai memanggil googlevideo.com
                                kotlinx.coroutines.MainScope().launch {
                                    delay(3500L)
                                    val vid = interceptedVideo.ifBlank { interceptedAudio }
                                    val aud = interceptedAudio.ifBlank { vid }
                                    if (vid.isNotBlank()) {
                                        completeWith(
                                            MediaEntry(
                                                id = videoId,
                                                title = view.title?.removeSuffix(" - YouTube")?.ifBlank { "Video YouTube" } ?: "Video YouTube",
                                                author = "YouTube",
                                                duration = "STREAM",
                                                thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                                                pageUrl = pageUrl,
                                                videoStreamUrl = vid,
                                                audioStreamUrl = aud
                                            )
                                        )
                                    } else {
                                        completeWith(null)
                                    }
                                }
                            }
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    runCatching {
                        webView.stopLoading()
                        webView.destroy()
                    }
                }

                webView.loadUrl("https://m.youtube.com/watch?v=$videoId")
            }
        }
    }

    private fun cleanGoogleVideoUrl(rawUrl: String): String {
        return rawUrl
            .replace(Regex("([?&])(range|rn|rbuf)=[^&]*"), "$1")
            .replace("?&", "?")
            .replace("&&", "&")
            .trimEnd('&', '?')
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
