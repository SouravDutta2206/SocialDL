package com.socialdl.src.core.download

import androidx.room.withTransaction
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.database.SocialDLDatabase
import com.socialdl.src.core.database.dao.DownloadDao
import com.socialdl.src.core.database.dao.DownloadJobDao
import com.socialdl.src.core.database.entity.DownloadHistoryEntity
import com.socialdl.src.core.database.entity.DownloadJobEntity
import com.socialdl.src.core.model.DownloadProgressInfo
import com.socialdl.src.core.model.DownloadStatus
import com.socialdl.src.core.model.uiKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed source of truth for download job lifecycle (P0-4).
 *
 * Every status write goes through this repository so the legal transition graph
 * is enforced in one place:
 *
 * ```
 * QUEUED -> DOWNLOADING -> MUXING -> EXPORTING -> COMPLETED(row deleted)
 *        \______________-> EXPORTING (single-stream, no mux)
 * any non-terminal state -> FAILED (FAILED is a persistent UI card, deletable)
 * ```
 *
 * There is no CANCELLED status: cancelling deletes the row; the worker's guard
 * reads rely on the row's absence to abort cleanly.
 */
@Singleton
class DownloadJobRepository @Inject constructor(
    private val database: SocialDLDatabase,
    private val jobDao: DownloadJobDao,
    private val downloadDao: DownloadDao,
) {
    private val tag = "DownloadJobRepository"

    /** All jobs visible to the UI (non-terminal plus FAILED cards), React to DB writes. */
    val observeJobs: Flow<List<DownloadJobEntity>> = jobDao.observeActiveJobs()

    /** Same as [observeJobs], projected to the UI-facing [DownloadProgressInfo]. */
    val observeProgressInfos: Flow<List<DownloadProgressInfo>> =
        observeJobs.map { jobs -> jobs.map { it.toProgressInfo() } }

    /**
     * Live jobs keyed by the shared MediaItem [uiKey] formula
     * (`"$sourceUrl_$carouselIndex"`), for Home dedup and inline overlays.
     */
    val observeProgressInfoByKey: Flow<Map<String, DownloadProgressInfo>> =
        observeJobs.map { jobs -> jobs.associate { it.uiKey() to it.toProgressInfo() } }

    suspend fun getJob(id: String): DownloadJobEntity? = jobDao.getJob(id)

    suspend fun insertJob(entity: DownloadJobEntity) = jobDao.insertJob(entity)

    /** Transition-checked full-row update (used for non-standard writes like workId, pendingUri). */
    suspend fun updateJob(entity: DownloadJobEntity) {
        val current = jobDao.getJob(entity.id)
        if (current == null) {
            Logger.w(tag, "updateJob: job ${entity.id} missing; refusing to resurrect")
            return
        }
        require(isTransitionAllowed(current.status, entity.status)) {
            "Illegal download status transition ${current.status} -> ${entity.status} for job ${entity.id}"
        }
        jobDao.updateJob(entity)
    }

    /** Progress ticks / phase updates written straight to Room (the UI observes Room). */
    suspend fun registerProgress(
        id: String,
        status: DownloadStatus,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long?
    ) {
        val current = jobDao.getJob(id) ?: return // cancelled mid-tick: row gone, ignore
        if (!isTransitionAllowed(current.status, status)) {
            Logger.w(tag, "registerProgress: illegal transition ${current.status} -> $status for job $id")
            return
        }
        jobDao.updateProgress(id, status, progress, downloadedBytes, totalBytes, now())
    }

    /**
     * Mark a job FAILED with a typed, user-facing [DownloadError.userMessage].
     * Writes atomically WITHOUT re-checking other race outcomes (idempotent: no-op
     * if the row is gone or already FAILED).
     */
    suspend fun markFailed(id: String, error: DownloadError) {
        jobDao.failJob(id, error.userMessage, now())
    }

    /**
     * Instant-cancel semantics: the row is deleted immediately — the active-jobs
     * Room flow drops it and the UI card vanishes. The worker discovers the missing
     * row via a guard read and aborts; the caller separately cancels WorkManager.
     */
    suspend fun deleteJob(id: String) = jobDao.deleteJob(id)

    /**
     * Success path, atomic in one Room transaction: insert the history row and
     * delete the job row, so the item leaves the active list exactly when the
     * history record appears.
     */
    suspend fun finishJobSuccessfully(job: DownloadJobEntity, outputUri: String, fileSizeBytes: Long) {
        database.withTransaction {
            downloadDao.insertHistory(
                DownloadHistoryEntity(
                    id = job.id,
                    platform = job.platform,
                    sourceUrl = job.sourceUrl,
                    title = job.title,
                    author = job.author,
                    thumbnailUrl = job.thumbnailUrl,
                    localUri = outputUri,
                    formatLabel = job.formatLabel,
                    container = job.container,
                    fileSizeBytes = fileSizeBytes,
                    completedAt = System.currentTimeMillis()
                )
            )
            jobDao.deleteJob(job.id)
        }
    }

    /** Snapshot for startup reconciliation. */
    suspend fun getActiveJobs(): List<DownloadJobEntity> = jobDao.getActiveJobs()

    /**
     * Destructive replace (§10 UX): a new enqueue of the same [sourceUrl]/carousel
     * slot discards any FAILED record for it first.
     */
    suspend fun discardFailedJob(sourceUrl: String, carouselIndex: Int) {
        jobDao.deleteFailedJob(sourceUrl, carouselIndex)
    }

    /**
     * Startup reconciliation failure write: jobs that lost their WorkManager work
     * (process death, system kill) become FAILED(INTERRUPTED) cards, consistent
     * with kill-on-fail.
     */
    suspend fun markInterruptedIfNotFailed(id: String) {
        jobDao.markFailed(id, DownloadError.Interrupted.userMessage, now())
    }

    private fun isTransitionAllowed(from: DownloadStatus, to: DownloadStatus): Boolean {
        if (from == to) return from in nonTerminal
        return when (to) {
            DownloadStatus.DOWNLOADING -> from == DownloadStatus.QUEUED
            DownloadStatus.MUXING -> from == DownloadStatus.DOWNLOADING
            DownloadStatus.EXPORTING -> from == DownloadStatus.DOWNLOADING || from == DownloadStatus.MUXING
            DownloadStatus.COMPLETED -> from == DownloadStatus.EXPORTING
            DownloadStatus.FAILED -> from in nonTerminal
            DownloadStatus.QUEUED -> false
            DownloadStatus.PAUSED,
            DownloadStatus.CANCELLED -> false
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private val nonTerminal = listOf(
        DownloadStatus.QUEUED,
        DownloadStatus.DOWNLOADING,
        DownloadStatus.MUXING,
        DownloadStatus.EXPORTING
    )
}

/** Shared identity formula: matches [MediaItem.uiKey] used by LazyColumn keys and batch selection. */
fun DownloadJobEntity.uiKey(): String = "${sourceUrl}_${carouselIndex}"

/** UI projection used by core:ui composables (MediaCard, ProgressOverlayCard). */
fun DownloadJobEntity.toProgressInfo(): DownloadProgressInfo = DownloadProgressInfo(
    id = id,
    status = status,
    progress = progress,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
    errorMessage = errorMessage
)
