package com.socialdl.src.core.model

import kotlinx.serialization.Serializable

/**
 * Represents a queued, active, or completed download.
 * Persisted via Room in Phase 9; used by the Downloads tab UI.
 */
@Serializable
data class DownloadTask(
    val id: String,
    val mediaItem: MediaItem,
    val selectedFormat: MediaFormat,
    val status: DownloadStatus,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val outputUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
    val groupId: String? = null,
)


@Serializable
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    MUXING,
    COMPLETED,
    FAILED,
    PAUSED,
    CANCELLED,
}
