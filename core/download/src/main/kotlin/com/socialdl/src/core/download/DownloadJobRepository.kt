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
import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MediaContainer
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

    /** Existing active job for the same source slot + stream, if any. */
    suspend fun findActiveDuplicate(
        sourceUrl: String,
        carouselIndex: Int,
        formatId: String
    ): DownloadJobEntity? = jobDao.findActiveDuplicate(sourceUrl, carouselIndex, formatId)

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

    /**
     * Progress ticks / phase updates written straight to Room (the UI observes
     * Room). The DAO applies the legal source statuses in the UPDATE's WHERE
     * clause, so a concurrent FAILED/cancel cannot be overwritten by a tick.
     *
     * @return true when the row was written; false when the job is gone or no
     *   longer in a status that may transition to [status].
     */
    suspend fun registerProgress(
        id: String,
        status: DownloadStatus,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long?
    ): Boolean {
        val allowedFrom = allowedProgressSources(status) ?: run {
            Logger.w(tag, "registerProgress: status $status is not a progress state for job $id")
            return false
        }
        val updated = jobDao.updateProgress(
            id = id,
            status = status,
            progress = progress,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            updatedAt = now(),
            allowedFrom = allowedFrom
        )
        if (updated == 0) {
            Logger.d(tag, "registerProgress: no active row for $id (cancelled or terminal); tick dropped")
            return false
        }
        return true
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
     * Success path, atomic in one Room transaction. The active job row is deleted
     * with an `EXPORTING` status predicate FIRST; history is inserted only when
     * that delete affected one row. A cancel (row already gone) can therefore
     * never produce a history record for a job the user cancelled.
     *
     * @return true when history was written.
     */
    suspend fun finishJobSuccessfully(
        job: DownloadJobEntity,
        outputUri: String,
        fileSizeBytes: Long
    ): Boolean = database.withTransaction {
        val deleted = jobDao.deleteJobIfStatus(job.id, DownloadStatus.EXPORTING)
        if (deleted != 1) {
            Logger.w(tag, "finishJobSuccessfully: job ${job.id} was cancelled/terminal; history not written")
            return@withTransaction false
        }
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
                formatKind = job.formatKind,
                container = job.container,
                mimeType = MediaContainer.mimeTypeFor(
                    container = job.container,
                    kind = FormatKind.fromOrNull(job.formatKind) ?: FormatKind.VIDEO
                ),
                fileSizeBytes = fileSizeBytes,
                completedAt = System.currentTimeMillis()
            )
        )
        true
    }

    /**
     * Persist JIT-resolved container/format metadata with a targeted UPDATE (no
     * full-row rewrite, no stale status). Returns false when the row disappeared
     * (user cancelled) while resolution was running.
     */
    suspend fun updateResolvedMetadata(
        id: String,
        container: String,
        formatId: String,
        formatKind: FormatKind,
        formatLabel: String
    ): Boolean {
        val updated = jobDao.updateResolvedMetadata(
            id = id,
            container = container,
            formatId = formatId,
            formatKind = formatKind.name,
            formatLabel = formatLabel,
            updatedAt = now()
        )
        if (updated == 0) {
            Logger.d(tag, "updateResolvedMetadata: no active row for $id (cancelled/terminal)")
            return false
        }
        return true
    }

    /** Persist the pending MediaStore row; false when the job was cancelled. */
    suspend fun setPendingUri(id: String, pendingUri: String): Boolean =
        jobDao.updatePendingUri(id, pendingUri, now()) > 0

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

    /**
     * Source statuses that may write a progress row for [target]. Same-status
     * ticks are allowed; anything after FAILED (or after cancellation, when the
     * row is gone) matches zero rows.
     */
    private fun allowedProgressSources(target: DownloadStatus): List<DownloadStatus>? = when (target) {
        DownloadStatus.DOWNLOADING -> listOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)
        DownloadStatus.MUXING -> listOf(DownloadStatus.DOWNLOADING, DownloadStatus.MUXING)
        DownloadStatus.EXPORTING -> listOf(
            DownloadStatus.DOWNLOADING,
            DownloadStatus.MUXING,
            DownloadStatus.EXPORTING
        )
        else -> null
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
