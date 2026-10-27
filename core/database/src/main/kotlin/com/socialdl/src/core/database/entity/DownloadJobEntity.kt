package com.socialdl.src.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.socialdl.src.core.model.DownloadStatus

/**
 * Durable state for a single download job. Room is the single source of truth for
 * the download lifecycle: the worker reads its full config from this row (WorkManager
 * Data carries only the job id — signed CDN URLs must not go through the ~10KB
 * Data limit), persists every transition, and deletes the row on success/cancel.
 *
 * Note: there is no CANCELLED status. Cancelling deletes the row immediately;
 * the row's absence is what signals "cancelled" to the worker's guard reads.
 */
@Entity(
    tableName = "download_jobs",
    indices = [
        Index(value = ["status"]),
        Index(value = ["sourceUrl"])
    ]
)
data class DownloadJobEntity(
    @PrimaryKey val id: String,
    /** WorkManager work-request UUID, kept for debugging / diagnostics. */
    val workId: String? = null,
    val sourceUrl: String,
    val title: String,
    val author: String? = null,
    val thumbnailUrl: String? = null,
    val platform: String,
    /** Carousel slot index — part of the uiKey dedup formula (see MediaItem.uiKey). */
    val carouselIndex: Int = 0,
    /** Exact source format ID, so a JIT re-resolution can pick the same stream. */
    val formatId: String = "",
    val formatLabel: String = "",
    val formatKind: String = "",
    /**
     * Output container. Blank means "resolve at download time" — used for
     * placeholder (batch/playlist) jobs so the JIT-resolved format decides.
     */
    val container: String = "",
    val groupId: String? = null,
    val status: DownloadStatus,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
    val outputUri: String? = null,
    val fileSizeBytes: Long = 0L,
    /** MediaStore row created with IS_PENDING=1 during the export step (P1-3). */
    val pendingUri: String? = null,
    // ---- Full download config (moved out of WorkManager Data; P0-4) ----
    val directUrl: String? = null,
    val requiresMuxing: Boolean = false,
    val audioDirectUrl: String? = null,
    val audioContainer: String? = null,
    val muxStrategy: String? = null,
    val videoChunkSize: Long = 0L,
    val audioChunkSize: Long = 0L,
    val isPlaceholder: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)
