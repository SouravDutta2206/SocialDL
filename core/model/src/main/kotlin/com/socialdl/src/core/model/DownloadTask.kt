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
    EXPORTING,
    COMPLETED,
    FAILED,
    PAUSED,
    CANCELLED,
}

/**
 * Minimal UI projection of an active download job. Lets core:ui composables and
 * feature screens render progress without depending on the database layer
 * (Room's [com.socialdl.src.core.database.entity.DownloadJobEntity] maps to this
 * via the DownloadJobRepository mapper in core:download).
 */
@Serializable
data class DownloadProgressInfo(
    val id: String,
    val status: DownloadStatus,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
)
