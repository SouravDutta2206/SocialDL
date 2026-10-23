package com.socialdl.src.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_history")
data class DownloadHistoryEntity(
    @PrimaryKey val id: String,
    val platform: String,
    val sourceUrl: String,
    val title: String,
    val author: String?,
    val thumbnailUrl: String?,
    val localUri: String,
    val formatLabel: String,
    val container: String,
    val fileSizeBytes: Long,
    val completedAt: Long = System.currentTimeMillis(),
)
