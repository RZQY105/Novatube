package com.app.novatube

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage

data class MediaEntry(
    val id: String,
    val title: String,
    val author: String,
    val duration: String,
    val thumbnailUrl: String,
    val streamUrl: String
)

val defaultCatalog = listOf(
    MediaEntry(
        id = "1",
        title = "Big Buck Bunny (1080p Full Stream)",
        author = "Blender Studio",
        duration = "09:56",
        thumbnailUrl = "https://peach.blender.org/wp-content/uploads/title_anouncement.jpg?x11217",
        streamUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
    ),
    MediaEntry(
        id = "2",
        title = "Sintel - Fantasy Short Film",
        author = "Blender Foundation",
        duration = "14:48",
        thumbnailUrl = "https://durian.blender.org/wp-content/uploads/2010/06/05.8b_comp_000272.jpg",
        streamUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4"
    ),
    MediaEntry(
        id = "3",
        title = "Tears of Steel - Sci-Fi Open Movie",
        author = "Mango Open Movie",
        duration = "12:14",
        thumbnailUrl = "https://mango.blender.org/wp-content/uploads/2012/09/01_thom_celia_bridge.jpg",
        streamUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4"
    )
)

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
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var catalog by remember { mutableStateOf(defaultCatalog) }
    var activeMedia by remember { mutableStateOf(defaultCatalog.first()) }
    var isAudioOnly by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showUrlDialog by remember { mutableStateOf(false) }

    // Minta izin notifikasi di Android 13+ agar pemutar latar belakang tetap muncul di layar kunci
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Hubungkan UI dengan PlaybackService
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()

        future.addListener({
            val mediaController = future.get()
            controller = mediaController
            startPlayback(mediaController, activeMedia)
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            MediaController.releaseFuture(future)
        }
    }

    val filteredList = remember(catalog, searchQuery) {
        if (searchQuery.isBlank()) catalog
        else catalog.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.author.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header Aplikasi + Input URL
        TopAppBar(
            title = {
                Text(
                    text = "NovaTube",
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            },
            actions = {
                IconButton(onClick = { showUrlDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "Putar URL",
                        tint = Color.White
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F0F))
        )

        // Pemutar Video / Tampilan Mode Audio Saja
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (!isAudioOnly && controller != null) {
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
                        text = "Mode Suara Saja Aktif",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Tetap berjalan saat keluar aplikasi atau layar mati",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Judul & Tombol Kontrol (Audio Saja, Simpan Video, Simpan Suara)
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
                        controller?.let { player ->
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, isAudioOnly)
                                .build()
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
                    onClick = {
                        MediaDownloader.download(
                            context = context,
                            url = activeMedia.streamUrl,
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
                    onClick = {
                        MediaDownloader.download(
                            context = context,
                            url = activeMedia.streamUrl,
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

        // Kolom Pencarian
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Cari video atau channel...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Daftar Video
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filteredList) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            activeMedia = item
                            controller?.let { startPlayback(it, item) }
                        }
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

    // Dialog Putar URL Kustom
    if (showUrlDialog) {
        var customTitle by remember { mutableStateOf("") }
        var customUrl by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Putar Tautan Stream") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customTitle,
                        onValueChange = { customTitle = it },
                        label = { Text("Judul Media") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("URL (.mp4 / .m3u8 / .mp3 / .m4a)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (customUrl.isNotBlank()) {
                            val newEntry = MediaEntry(
                                id = System.currentTimeMillis().toString(),
                                title = customTitle.ifBlank { "Custom Stream" },
                                author = "Direct Link",
                                duration = "LIVE/STREAM",
                                thumbnailUrl = "",
                                streamUrl = customUrl.trim()
                            )
                            catalog = listOf(newEntry) + catalog
                            activeMedia = newEntry
                            controller?.let { startPlayback(it, newEntry) }
                            showUrlDialog = false
                        }
                    }
                ) {
                    Text("Putar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}

private fun startPlayback(player: Player, media: MediaEntry) {
    val metadata = MediaMetadata.Builder()
        .setTitle(media.title)
        .setArtist(media.author)
        .build()

    val item = MediaItem.Builder()
        .setUri(media.streamUrl)
        .setMediaMetadata(metadata)
        .build()

    player.setMediaItem(item)
    player.prepare()
    player.play()
}
