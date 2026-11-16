package com.app.novatube

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0F0F0F),
                    surface = Color(0xFF1A1A1A),
                    primary = Color(0xFFFF3D00)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NovaTubeApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovaTubeApp() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var catalog by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var activeMedia by remember {
        mutableStateOf(
            MediaEntry(
                id = "welcome",
                title = "Cari video atau tempel link YouTube di bawah",
                author = "NovaTube",
                duration = "00:00",
                thumbnailUrl = "",
                pageUrl = ""
            )
        )
    }
    var isAudioOnly by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Muat daftar video populer awal
        isLoading = true
        runCatching {
            YouTubeHelper.searchVideos("trending indonesia")
        }.onSuccess { results ->
            catalog = results
        }
        isLoading = false
    }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()

        future.addListener({
            controller = future.get()
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            MediaController.releaseFuture(future)
        }
    }

    fun playEntry(entry: MediaEntry) {
        scope.launch {
            isLoading = true
            try {
                val readyMedia = if (entry.videoStreamUrl.isBlank() && entry.pageUrl.isNotBlank()) {
                    YouTubeHelper.extractStreams(entry.pageUrl)
                } else {
                    entry
                }
                activeMedia = readyMedia
                controller?.let { startPlayback(it, readyMedia, isAudioOnly) }
            } catch (e: Exception) {
                Toast.makeText(context, "Gagal memuat video: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
            }
        }
    }

    fun handleSearchOrUrl() {
        val input = searchQuery.trim()
        if (input.isBlank()) return
        focusManager.clearFocus()

        scope.launch {
            isLoading = true
            try {
                if (YouTubeHelper.isYouTubeUrl(input)) {
                    // Langsung ekstrak & putar tautan youtu.be / youtube.com
                    val extracted = YouTubeHelper.extractStreams(input)
                    activeMedia = extracted
                    catalog = listOf(extracted) + catalog.filterNot { it.id == extracted.id }
                    controller?.let { startPlayback(it, extracted, isAudioOnly) }
                } else {
                    // Cari video YouTube berdasarkan kata kunci
                    val results = YouTubeHelper.searchVideos(input)
                    catalog = results
                    if (results.isEmpty()) {
                        Toast.makeText(context, "Tidak ada hasil ditemukan", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Gagal memproses: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "NovaTube",
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            },
            actions = {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = Color(0xFFFF3D00)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F0F))
        )

        // Area Pemutar Video / Mode Suara Saja
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (!isAudioOnly && controller != null && activeMedia.videoStreamUrl.isNotBlank()) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = controller
                            useController = true
                        }
                    },
                    update = { it.player = controller },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Headphones,
                        contentDescription = null,
                        tint = Color(0xFFFF3D00),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isAudioOnly) "Mode Suara Saja Aktif" else "Siap Memutar",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Suara tetap jalan saat aplikasi ditutup atau layar mati",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Info Video & Tombol Kontrol
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = activeMedia.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = activeMedia.author,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = isAudioOnly,
                    onClick = {
                        isAudioOnly = !isAudioOnly
                        if (activeMedia.videoStreamUrl.isNotBlank()) {
                            controller?.let { player ->
                                val currentPos = player.currentPosition
                                startPlayback(player, activeMedia, isAudioOnly, currentPos)
                            }
                        }
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Headphones,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text(if (isAudioOnly) "Audio: ON" else "Suara Saja") }
                )

                FilledTonalButton(
                    enabled = activeMedia.videoStreamUrl.isNotBlank(),
                    onClick = {
                        MediaDownloader.download(
                            context = context,
                            url = activeMedia.videoStreamUrl,
                            title = activeMedia.title,
                            audioOnly = false
                        )
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Video")
                }

                FilledTonalButton(
                    enabled = activeMedia.audioStreamUrl.isNotBlank(),
                    onClick = {
                        MediaDownloader.download(
                            context = context,
                            url = activeMedia.audioStreamUrl,
                            title = activeMedia.title,
                            audioOnly = true
                        )
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Suara")
                }
            }
        }

        // Kolom Pencarian + Tempel Link YouTube
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Cari video atau tempel link youtu.be...") },
            leadingIcon = {
                IconButton(onClick = { handleSearchOrUrl() }) {
                    Icon(Icons.Default.Search, contentDescription = "Cari")
                }
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Hapus")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { handleSearchOrUrl() }),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Daftar Video Hasil Pencarian
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(catalog) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { playEntry(item) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        AsyncImage(
                            model = item.thumbnailUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(136.dp)
                                .height(76.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.DarkGray)
                        )
                        Text(
                            text = item.duration,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}

private fun startPlayback(
    player: Player,
    media: MediaEntry,
    audioOnly: Boolean,
    startPositionMs: Long = 0L
) {
    val targetUrl = if (audioOnly) media.audioStreamUrl else media.videoStreamUrl
    if (targetUrl.isBlank()) return

    val metadata = MediaMetadata.Builder()
        .setTitle(media.title)
        .setArtist(media.author)
        .build()

    val item = MediaItem.Builder()
        .setUri(targetUrl)
        .setMediaMetadata(metadata)
        .build()

    player.setMediaItem(item, startPositionMs)
    player.prepare()
    player.play()
}
