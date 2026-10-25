package com.socialdl.src.core.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.database.entity.DownloadJobEntity
import com.socialdl.src.core.model.DownloadProgressInfo
import com.socialdl.src.core.model.DownloadStatus
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facade for enqueueing/cancelling downloads. Room (via [DownloadJobRepository])
 * is the source of truth for the lifecycle; WorkManager Data carries only the
 * job id (P0-4 / P1-2), so signed CDN URLs live in the Room row exclusively.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: DownloadEventBus,
    private val jobRepository: DownloadJobRepository,
) {
    private val tag = "DownloadManager"
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Logger.e(tag, "Uncaught error in DownloadManager scope", e)
        }
    )

    /** Live jobs (non-terminal + FAILED cards) straight from Room. */
    val jobs: Flow<List<DownloadJobEntity>> = jobRepository.observeJobs

    /** UI projection flow (used by Home for inline overlays keyed by [DownloadJobEntity.uiKey]). */
    val progressInfos: Flow<List<DownloadProgressInfo>> = jobRepository.observeProgressInfos

    /** Same as [progressInfos], keyed by the MediaItem uiKey identity formula. */
    val progressInfoByKey: Flow<Map<String, DownloadProgressInfo>> =
        jobRepository.observeProgressInfoByKey

    init {
        scope.launch { reconcileOnStartup() }
    }

    /**
     * Builds the durable job row, writes it to Room FIRST, then enqueues a
     * WorkRequest whose input contains only KEY_JOB_ID. The worker reads its
     * entire config from the row, so enqueue-before-insert must never happen.
     */
    suspend fun enqueueDownload(
        mediaItem: MediaItem,
        selectedFormat: MediaFormat,
        container: String? = null,
        customTitle: String? = null,
        customAuthor: String? = null,
        groupId: String? = null
    ): String {
        val jobId = UUID.randomUUID().toString()
        val finalContainer = if (!container.isNullOrBlank() && container != "Default") {
            container
        } else {
            selectedFormat.container
        }

        val title = customTitle ?: mediaItem.title
        val author = customAuthor ?: mediaItem.author

        // If format requires muxing, resolve paired audio stream upfront
        // (non-placeholder path; placeholders re-resolve in the worker via JIT).
        val pairedAudio = if (selectedFormat.requiresMuxing) {
            mediaItem.audioFormats.find { it.id == selectedFormat.pairedAudioFormatId }
                ?: mediaItem.audioFormats.firstOrNull()
        } else {
            null
        }

        Logger.i(
            tag,
            "Enqueuing job $jobId for '$title', format: ${selectedFormat.label}, container: $finalContainer, " +
                "pairedAudio=${pairedAudio?.id}, groupId=$groupId"
        )

        val job = DownloadJobEntity(
            id = jobId,
            sourceUrl = mediaItem.sourceUrl,
            title = title,
            author = author,
            thumbnailUrl = mediaItem.thumbnailUrl,
            platform = mediaItem.platform.name,
            carouselIndex = mediaItem.carouselIndex ?: 0,
            formatLabel = selectedFormat.label,
            formatKind = selectedFormat.kind.name,
            container = finalContainer,
            groupId = groupId,
            status = DownloadStatus.QUEUED,
            progress = 0f,
            totalBytes = selectedFormat.approxSizeBytes,
            directUrl = selectedFormat.directUrl?.takeIf { it.isNotBlank() },
            requiresMuxing = selectedFormat.requiresMuxing,
            audioDirectUrl = pairedAudio?.directUrl?.takeIf { it.isNotBlank() },
            audioContainer = pairedAudio?.container ?: "M4A",
            muxStrategy = selectedFormat.muxStrategy?.name,
            videoChunkSize = selectedFormat.httpChunkSizeBytes ?: 0L,
            audioChunkSize = pairedAudio?.httpChunkSizeBytes ?: 0L,
            isPlaceholder = selectedFormat.isPlaceholder,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        jobRepository.insertJob(job)

        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_JOB_ID to jobId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(jobId)
            .build()

        try {
            WorkManager.getInstance(context).enqueue(workRequest)
        } catch (e: Exception) {
            // Do not leave a QUEUED zombie row: delete it and rethrow so callers
            // (batch counting) report a truthful enqueued count.
            Logger.e(tag, "WorkManager enqueue failed for job $jobId; deleting row", e)
            jobRepository.deleteJob(jobId)
            throw e
        }
        return jobId
    }

    /**
     * Instant cancellation (P0-4): delete the Room row FIRST — the active-jobs
     * flow drops the row and the UI item vanishes immediately — then cancel the
     * WorkManager job. A worker already running discovers the missing row via its
     * guard read and aborts; scratch is cleaned in its finally block.
     */
    suspend fun cancelDownload(jobId: String) {
        Logger.i(tag, "Cancel requested for job $jobId")
        jobRepository.deleteJob(jobId)
        WorkManager.getInstance(context).cancelAllWorkByTag(jobId)
    }

    /** Same deletion for the FAILED card's "X" (background work is already dead). */
    suspend fun removeFailedJob(jobId: String) {
        jobRepository.deleteJob(jobId)
    }

    /**
     * Startup reconciliation:
     * 1. Non-terminal Room jobs whose WorkManager work is missing or finished
     *    (process death, system kill) are marked FAILED(INTERRUPTED) — they
     *    surface as normal FAILED cards (X removable), consistent with kill-on-fail.
     * 2. Orphan scratch dirs (`cache/downloads/<id>` with no job row) are swept.
     */
    private suspend fun reconcileOnStartup() {
        try {
            val workManager = WorkManager.getInstance(context)
            val jobs = jobRepository.getActiveJobs()
            jobs.forEach { job ->
                val workAlive = try {
                    workManager.getWorkInfosByTag(job.id).get()
                        .any { !it.state.isFinished }
                } catch (e: Exception) {
                    Logger.w(tag, "WorkManager lookup failed for job ${job.id}", e)
                    false
                }
                if (!workAlive) {
                    Logger.i(tag, "Reconciling dead job ${job.id} (${job.status}) as FAILED(INTERRUPTED)")
                    // P1-3 sweep: a MediaStore row created with IS_PENDING=1 during an
                    // interrupted export is deleted if still pending.
                    job.pendingUri?.let { uri ->
                        if (deleteIfStillPending(uri)) {
                            Logger.i(tag, "Swept orphaned pending MediaStore row for job ${job.id}")
                        }
                    }
                    jobRepository.markInterruptedIfNotFailed(job.id)
                }
            }

            val knownIds = jobRepository.getActiveJobs().map { it.id }.toSet()
            File(context.cacheDir, "downloads").listFiles()?.forEach { dir ->
                if (dir.name !in knownIds) {
                    Logger.i(tag, "Sweeping orphan scratch dir ${dir.name}")
                    dir.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            Logger.e(tag, "Startup reconciliation failed", e)
        }
    }

    /**
     * P1-3: deletes a MediaStore row only when it is still IS_PENDING=1 — a row
     * that already finished its export belongs to the user and is left alone.
     */
    private fun deleteIfStillPending(pendingUri: String): Boolean = runCatching {
        val uri = android.net.Uri.parse(pendingUri)
        val isPending = context.contentResolver
            .query(uri, arrayOf(android.provider.MediaStore.MediaColumns.IS_PENDING), null, null, null)
            ?.use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 } ?: false
        if (isPending) {
            com.socialdl.src.core.common.util.MediaStorageUtils.deleteMediaFromStorage(context, pendingUri)
        } else {
            false
        }
    }.onFailure { e -> Logger.w(tag, "Pending-row sweep failed for $pendingUri", e) }
        .getOrDefault(false)
}
