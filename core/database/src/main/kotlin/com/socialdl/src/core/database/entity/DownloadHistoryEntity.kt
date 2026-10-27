package com.socialdl.src.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "download_history",
    indices = [Index(value = ["completedAt"])]
)
data class DownloadHistoryEntity(
    @PrimaryKey val id: String,
    val platform: String,
    val sourceUrl: String,
    val title: String,
    val author: String?,
    val thumbnailUrl: String?,
    val localUri: String,
    val formatLabel: String,
    /** Typed media kind at download time (VIDEO/AUDIO/IMAGE), for open/share MIME. */
    val formatKind: String = "",
    val container: String,
    /** Resolved MIME type; blank for legacy rows, which fall back to container mapping. */
    val mimeType: String = "",
    val fileSizeBytes: Long,
    val completedAt: Long = System.currentTimeMillis(),
    /**
     * First time the media file was observed missing (epoch millis). Null when the
     * file exists/unknown. Cleanup waits out a grace period instead of deleting on
     * the first provider hiccup.
     */
    val missingSince: Long? = null,
)
