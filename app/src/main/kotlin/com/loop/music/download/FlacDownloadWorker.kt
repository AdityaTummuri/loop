/**
 * Loop Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.loop.music.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class FlacDownloadWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_MEDIA_ID = "media_id"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_ALBUM = "album"
        const val KEY_STREAM_URL = "stream_url"
        const val KEY_FORMAT_NAME = "format_name"
        const val KEY_COVER_URL = "cover_url"
        const val KEY_DURATION = "duration"

        private const val CHANNEL_ID = "loop_download_channel"
        private const val NOTIFICATION_ID = 2026
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val mediaId = inputData.getString(KEY_MEDIA_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Unknown Track"
        val artist = inputData.getString(KEY_ARTIST) ?: "Unknown Artist"
        val album = inputData.getString(KEY_ALBUM) ?: "Loop Music"
        val streamUrl = inputData.getString(KEY_STREAM_URL) ?: return Result.failure()
        val formatName = inputData.getString(KEY_FORMAT_NAME) ?: DownloadAudioFormat.HI_RES_96.name
        val duration = inputData.getInt(KEY_DURATION, 0)

        val format = try {
            DownloadAudioFormat.valueOf(formatName)
        } catch (_: Exception) {
            DownloadAudioFormat.HI_RES_96
        }

        createNotificationChannel()
        setForeground(createForegroundInfo(title, artist, 0))

        return try {
            val sanitizedTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val sanitizedArtist = artist.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val fileName = "$sanitizedArtist - $sanitizedTitle.${format.extension}"

            val musicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "Loop",
            )
            if (!musicDir.exists()) {
                musicDir.mkdirs()
            }
            val destinationFile = File(musicDir, fileName)

            val request = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("FlacDownloadWorker").w("Download request failed with HTTP %d", response.code)
                    return Result.retry()
                }

                val body = response.body ?: return Result.failure()
                val contentLength = body.contentLength()
                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(destinationFile)

                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                var totalBytesRead = 0L

                outputStream.use { out ->
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (contentLength > 0) {
                            val progress = ((totalBytesRead * 100) / contentLength).toInt()
                            setProgress(workDataOf("progress" to progress))
                        }
                    }
                }
            }

            // Register in MediaStore so it appears in player library & system
            registerInMediaStore(
                file = destinationFile,
                title = title,
                artist = artist,
                album = album,
                mimeType = format.mimeType,
                durationMs = duration * 1000L,
            )

            showCompletedNotification(title, artist)
            Timber.tag("FlacDownloadWorker").i("Successfully downloaded %s to %s", title, destinationFile.absolutePath)
            Result.success(workDataOf("file_path" to destinationFile.absolutePath))
        } catch (e: Exception) {
            Timber.tag("FlacDownloadWorker").e(e, "Error downloading track %s", title)
            Result.failure()
        }
    }

    private fun registerInMediaStore(
        file: File,
        title: String,
        artist: String,
        album: String,
        mimeType: String,
        durationMs: Long,
    ) {
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(mimeType),
            ) { _, _ -> }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.TITLE, title)
                    put(MediaStore.Audio.Media.ARTIST, artist)
                    put(MediaStore.Audio.Media.ALBUM, album)
                    put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.DURATION, durationMs)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Loop")
                }
                context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            }
        } catch (e: Exception) {
            Timber.tag("FlacDownloadWorker").w(e, "MediaStore registration warning")
        }
    }

    private fun createForegroundInfo(title: String, artist: String, progress: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Downloading $title")
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun showCompletedNotification(title: String, artist: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Download Complete")
            .setContentText("$title - $artist")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Lossless Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress of FLAC audio downloads"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
