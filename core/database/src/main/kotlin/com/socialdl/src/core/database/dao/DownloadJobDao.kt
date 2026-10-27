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

    /**
     * Snapshot for startup reconciliation. FAILED cards are excluded: they have no
     * live work left and must not be re-failed/cancelled on every launch.
     */
    @Query(
        "SELECT * FROM download_jobs " +
            "WHERE status IN ('QUEUED','DOWNLOADING','MUXING','EXPORTING')"
    )
    suspend fun getActiveJobs(): List<DownloadJobEntity>

    @Query("SELECT * FROM download_jobs WHERE sourceUrl = :sourceUrl")
    suspend fun getJobsBySourceUrl(sourceUrl: String): List<DownloadJobEntity>

    /** Idempotency lookup: the same stream slot already queued/running. */
    @Query(
        "SELECT * FROM download_jobs " +
            "WHERE sourceUrl = :sourceUrl AND carouselIndex = :carouselIndex AND formatId = :formatId " +
            "AND status IN ('QUEUED','DOWNLOADING','MUXING','EXPORTING') LIMIT 1"
    )
    suspend fun findActiveDuplicate(
        sourceUrl: String,
        carouselIndex: Int,
        formatId: String
    ): DownloadJobEntity?

    /**
     * Conditional progress write. [allowedFrom] encodes the legal transition for
     * the target status, so progress ticks can never resurrect a FAILED row or
     * move a completed/cancelled job backwards. Returns affected-row count.
     */
    @Query(
        "UPDATE download_jobs SET status = :status, progress = :progress, " +
            "downloadedBytes = :downloadedBytes, totalBytes = :totalBytes, updatedAt = :updatedAt " +
            "WHERE id = :id AND status IN (:allowedFrom)"
    )
    suspend fun updateProgress(
        id: String,
        status: DownloadStatus,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long?,
        updatedAt: Long,
        allowedFrom: List<DownloadStatus>
    ): Int

    /** Success-path guard: delete only while still exporting. Returns affected rows. */
    @Query("DELETE FROM download_jobs WHERE id = :id AND status = :status")
    suspend fun deleteJobIfStatus(id: String, status: DownloadStatus): Int

    /** Persist the pending MediaStore row without rewriting the whole job. */
    @Query(
        "UPDATE download_jobs SET pendingUri = :pendingUri, updatedAt = :updatedAt " +
            "WHERE id = :id AND status IN ('QUEUED','DOWNLOADING','MUXING','EXPORTING')"
    )
    suspend fun updatePendingUri(id: String, pendingUri: String, updatedAt: Long): Int

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

    /**
     * Persist JIT-resolved stream metadata without rewriting the whole row (which
     * would clobber concurrent status/progress writes). Returns the number of rows
     * affected: 0 means the row was deleted (user cancelled) while resolving.
     */
    @Query(
        "UPDATE download_jobs SET container = :container, formatId = :formatId, " +
            "formatKind = :formatKind, formatLabel = :formatLabel, updatedAt = :updatedAt " +
            "WHERE id = :id AND status IN ('QUEUED','DOWNLOADING','MUXING','EXPORTING')"
    )
    suspend fun updateResolvedMetadata(
        id: String,
        container: String,
        formatId: String,
        formatKind: String,
        formatLabel: String,
        updatedAt: Long
    ): Int

    /** Final failure write: status + message in one statement (used after guard reads).
     *  Idempotent like [markFailed]: no-op if the row is gone or already FAILED. */
    @Query(
        "UPDATE download_jobs SET status = 'FAILED', errorMessage = :message, updatedAt = :updatedAt " +
            "WHERE id = :id AND status != 'FAILED'"
    )
    suspend fun failJob(id: String, message: String?, updatedAt: Long)
}
