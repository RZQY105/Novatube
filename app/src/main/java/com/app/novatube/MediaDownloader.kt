package com.app.novatube

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast

object MediaDownloader {

    fun download(
        context: Context,
        url: String,
        title: String,
        audioOnly: Boolean
    ) {
        try {
            val safeName = title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(50)
            val ext = if (audioOnly) "m4a" else "mp4"
            val folder = if (audioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
            val subPath = "NovaTube/${safeName}_${System.currentTimeMillis()}.$ext"

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(if (audioOnly) "Audio: $title" else "Video: $title")
                .setDescription("Menyimpan ke perangkat...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(folder, subPath)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)

            val lokasi = if (audioOnly) "Music/NovaTube" else "Movies/NovaTube"
            Toast.makeText(context, "Mengunduh ke folder $lokasi", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "URL tidak valid untuk diunduh", Toast.LENGTH_SHORT).show()
        }
    }
}
