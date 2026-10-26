package com.socialdl.src.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.socialdl.src.core.database.entity.DownloadJobEntity
import com.socialdl.src.core.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the durable download_jobs table — Room is the source of truth for the
 * download lifecycle. Customers: [DownloadManager] (enqueue/cancel/reconciliation)
 * and [DownloadWorker] (transitions/progress/guard reads).
 */
@Dao
interface DownloadJobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJob(entity: DownloadJobEntity)

    @Update
    suspend fun updateJob(entity: DownloadJobEntity)

    @Query("SELECT * FROM download_jobs WHERE id = :id")
    suspend fun getJob(id: String): DownloadJobEntity?

    @Query("DELETE FROM download_jobs WHERE id = :id")
    suspend fun deleteJob(id: String)

    /**
     * Destructive replace: starting the same download again discards a lingering
     * FAILED record for it (matched by the uiKey identity — sourceUrl + carousel
     * slot), so a retry never requires an X-to-remove round-trip first.
     */
    @Query(
        "DELETE FROM download_jobs WHERE sourceUrl = :sourceUrl AND carouselIndex = :carouselIndex AND status = 'FAILED'"
    )
    suspend fun deleteFailedJob(sourceUrl: String, carouselIndex: Int)

    /** Jobs visible in the UI: every non-terminal status plus FAILED failure cards. */
    @Query(
        "SELECT * FROM download_jobs " +
            "WHERE status IN ('QUEUED','DOWNLOADING','MUXING','EXPORTING','FAILED') " +
            "ORDER BY createdAt ASC"
    )
    fun observeActiveJobs(): Flow<List<DownloadJobEntity>>

    /** Snapshot for startup reconciliation (jobs that ran to completion or were deleted are absent). */
    @Query(
        "SELECT * FROM download_jobs " +
            "WHERE status IN ('QUEUED','DOWNLOADING','MUXING','EXPORTING','FAILED')"
    )
    suspend fun getActiveJobs(): List<DownloadJobEntity>

    @Query("SELECT * FROM download_jobs WHERE sourceUrl = :sourceUrl")
    suspend fun getJobsBySourceUrl(sourceUrl: String): List<DownloadJobEntity>

    @Query(
        "UPDATE download_jobs SET status = :status, progress = :progress, " +
            "downloadedBytes = :downloadedBytes, totalBytes = :totalBytes, updatedAt = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun updateProgress(
        id: String,
        status: DownloadStatus,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long?,
        updatedAt: Long
    )

    /** Marks a job FAILED in one statement unless it already is; never resurrects a FAILED row. */
    @Query(
        "UPDATE download_jobs SET status = 'FAILED', errorMessage = :message, updatedAt = :updatedAt " +
            "WHERE id = :id AND status != 'FAILED'"
    )
    suspend fun markFailed(id: String, message: String?, updatedAt: Long)

    @Query(
        "UPDATE download_jobs SET status = :status, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateStatus(id: String, status: DownloadStatus, updatedAt: Long)

    /** Final failure write: status + message in one statement (used after guard reads).
     *  Idempotent like [markFailed]: no-op if the row is gone or already FAILED. */
    @Query(
        "UPDATE download_jobs SET status = 'FAILED', errorMessage = :message, updatedAt = :updatedAt " +
            "WHERE id = :id AND status != 'FAILED'"
    )
    suspend fun failJob(id: String, message: String?, updatedAt: Long)
}
