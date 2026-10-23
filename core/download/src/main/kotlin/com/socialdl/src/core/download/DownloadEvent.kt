package com.socialdl.src.core.download

import com.socialdl.src.core.model.DownloadTask

enum class DownloadPhase {
    DOWNLOADING,
    MUXING,
}

sealed interface DownloadEvent {
    data class Queued(val task: DownloadTask) : DownloadEvent
    data class Running(
        val taskId: String,
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long?,
        val phase: DownloadPhase
    ) : DownloadEvent
    data class Completed(
        val taskId: String,
        val outputUri: String,
        val fileSizeBytes: Long
    ) : DownloadEvent
    data class Failed(
        val taskId: String,
        val error: String
    ) : DownloadEvent
    data class Paused(val taskId: String) : DownloadEvent
    data class Cancelled(val taskId: String) : DownloadEvent
}
