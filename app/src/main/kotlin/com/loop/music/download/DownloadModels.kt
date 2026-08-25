/**
 * Loop Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.loop.music.download

enum class DownloadAudioFormat(
    val label: String,
    val description: String,
    val extension: String,
    val mimeType: String,
    val bitDepth: Int,
    val sampleRate: Int,
    val sortOrder: Int,
) {
    HI_RES_192(
        label = "24-bit / 192kHz FLAC",
        description = "Studio Master Hi-Res Audio",
        extension = "flac",
        mimeType = "audio/flac",
        bitDepth = 24,
        sampleRate = 192000,
        sortOrder = 4,
    ),
    HI_RES_96(
        label = "24-bit / 96kHz FLAC",
        description = "Hi-Res Lossless Audio",
        extension = "flac",
        mimeType = "audio/flac",
        bitDepth = 24,
        sampleRate = 96000,
        sortOrder = 3,
    ),
    LOSSLESS_44(
        label = "16-bit / 44.1kHz FLAC",
        description = "CD Quality Lossless",
        extension = "flac",
        mimeType = "audio/flac",
        bitDepth = 16,
        sampleRate = 44100,
        sortOrder = 2,
    ),
    AAC_256(
        label = "256kbps AAC",
        description = "Standard High Quality",
        extension = "m4a",
        mimeType = "audio/mp4",
        bitDepth = 16,
        sampleRate = 44100,
        sortOrder = 1,
    );

    companion object {
        val sortedDescending: List<DownloadAudioFormat>
            get() = entries.sortedByDescending { it.sortOrder }
    }
}

data class DownloadTrackInfo(
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val isrc: String? = null,
    val spotifyTrackId: String? = null,
    val coverUrl: String? = null,
    val durationSeconds: Int = 0,
)

data class DownloadQualityOption(
    val format: DownloadAudioFormat,
    val streamUrl: String? = null,
    val providerName: String = "Tidal / Qobuz",
    val estimatedSizeBytes: Long = 0L,
    val isAvailable: Boolean = true,
)
