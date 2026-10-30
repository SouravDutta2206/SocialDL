package com.socialdl.src.core.model

import kotlinx.serialization.Serializable

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
