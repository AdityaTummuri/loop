/**
 * Loop Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.loop.music.download

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.metrolist.music.network.flac.FlacAudioQuality
import com.metrolist.music.network.flac.FlacRegistryManager
import com.metrolist.music.network.flac.FlacStreamRepository
import com.metrolist.music.network.flac.FlacTrackQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object FlacDownloadManager {

    /**
     * Resolves all available audio formats for a given track from extension endpoints,
     * sorted in descending order of fidelity:
     * 1. 24-bit / 192kHz FLAC (Hi-Res)
     * 2. 24-bit / 96kHz FLAC (Hi-Res)
     * 3. 16-bit / 44.1kHz FLAC (Lossless)
     * 4. 256kbps AAC (Standard)
     */
    suspend fun resolveAvailableFormats(
        track: DownloadTrackInfo,
    ): List<DownloadQualityOption> = withContext(Dispatchers.IO) {
        // Ensure extension registry is initialized
        if (!FlacRegistryManager.isLoaded) {
            FlacRegistryManager.initialize()
        }

        val formats = mutableListOf<DownloadQualityOption>()

        // 1. Try Hi-Res 24-bit / 192kHz (or Hi-Res 24/96)
        val query24 = FlacTrackQuery(
            mediaId = track.mediaId,
            title = track.title,
            artists = listOf(track.artist),
            album = track.album,
            isrc = track.isrc,
            spotifyTrackId = track.spotifyTrackId,
            durationMs = track.durationSeconds * 1000L,
            quality = FlacAudioQuality.HI_RES_24,
        )

        val resolved24 = runCatching {
            FlacStreamRepository.resolveFlacStream(query24)
        }.getOrNull()

        if (resolved24 != null && resolved24.mediaUri.isNotBlank()) {
            val is192 = (resolved24.sampleRate ?: 96000) >= 192000
            formats.add(
                DownloadQualityOption(
                    format = if (is192) DownloadAudioFormat.HI_RES_192 else DownloadAudioFormat.HI_RES_96,
                    streamUrl = resolved24.mediaUri,
                    providerName = resolved24.providerName.uppercase(),
                    isAvailable = true,
                )
            )
            // Also provide 96kHz option if 192kHz is primary
            if (is192) {
                formats.add(
                    DownloadQualityOption(
                        format = DownloadAudioFormat.HI_RES_96,
                        streamUrl = resolved24.mediaUri,
                        providerName = resolved24.providerName.uppercase(),
                        isAvailable = true,
                    )
                )
            }
        }

        // 2. CD Quality 16-bit / 44.1kHz FLAC
        val query16 = query24.copy(quality = FlacAudioQuality.LOSSLESS_16)
        val resolved16 = runCatching {
            FlacStreamRepository.resolveFlacStream(query16)
        }.getOrNull()

        if (resolved16 != null && resolved16.mediaUri.isNotBlank()) {
            formats.add(
                DownloadQualityOption(
                    format = DownloadAudioFormat.LOSSLESS_44,
                    streamUrl = resolved16.mediaUri,
                    providerName = resolved16.providerName.uppercase(),
                    isAvailable = true,
                )
            )
        } else if (resolved24 != null) {
            formats.add(
                DownloadQualityOption(
                    format = DownloadAudioFormat.LOSSLESS_44,
                    streamUrl = resolved24.mediaUri,
                    providerName = resolved24.providerName.uppercase(),
                    isAvailable = true,
                )
            )
        }

        // 3. Always include 256kbps AAC option (from stream or fallback)
        formats.add(
            DownloadQualityOption(
                format = DownloadAudioFormat.AAC_256,
                streamUrl = resolved16?.mediaUri ?: resolved24?.mediaUri,
                providerName = "Standard High Quality",
                isAvailable = true,
            )
        )

        // Return distinct and sorted descending
        formats.distinctBy { it.format }.sortedByDescending { it.format.sortOrder }
    }

    /**
     * Enqueues a background download task with WorkManager.
     */
    fun enqueueDownload(
        context: Context,
        track: DownloadTrackInfo,
        option: DownloadQualityOption,
    ) {
        val streamUrl = option.streamUrl
        if (streamUrl.isNullOrBlank()) {
            Timber.tag("FlacDownloadManager").w("Cannot enqueue download: empty stream URL")
            return
        }

        val inputData = Data.Builder()
            .putString(FlacDownloadWorker.KEY_MEDIA_ID, track.mediaId)
            .putString(FlacDownloadWorker.KEY_TITLE, track.title)
            .putString(FlacDownloadWorker.KEY_ARTIST, track.artist)
            .putString(FlacDownloadWorker.KEY_ALBUM, track.album ?: "Loop")
            .putString(FlacDownloadWorker.KEY_STREAM_URL, streamUrl)
            .putString(FlacDownloadWorker.KEY_FORMAT_NAME, option.format.name)
            .putString(FlacDownloadWorker.KEY_COVER_URL, track.coverUrl)
            .putInt(FlacDownloadWorker.KEY_DURATION, track.durationSeconds)
            .build()

        val downloadRequest = OneTimeWorkRequestBuilder<FlacDownloadWorker>()
            .setInputData(inputData)
            .addTag("flac_download_${track.mediaId}")
            .build()

        WorkManager.getInstance(context).enqueue(downloadRequest)
        Timber.tag("FlacDownloadManager").i("Enqueued download for %s (%s)", track.title, option.format.label)
    }
}
