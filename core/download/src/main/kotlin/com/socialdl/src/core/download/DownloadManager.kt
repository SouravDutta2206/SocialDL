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
import com.socialdl.src.core.model.MediaContainer
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.model.MuxStrategy
import com.socialdl.src.core.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val settingsRepository: SettingsRepository,
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

    /**
     * Barrier: reconciliation must finish before a new enqueue can be inserted.
     * Otherwise a fast first enqueue could be observed by the startup snapshot
     * and immediately failed/cancelled as an "orphan".
     */
    private val startupReconciled = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                reconcileOnStartup()
            } finally {
                startupReconciled.complete(Unit)
            }
        }
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
        startupReconciled.await()

        // Idempotency: tapping download twice for the same stream slot must not
        // launch two workers or produce two files/history rows.
        jobRepository.findActiveDuplicate(
            sourceUrl = mediaItem.sourceUrl,
            carouselIndex = mediaItem.carouselIndex ?: 0,
            formatId = selectedFormat.id
        )?.let { existing ->
            Logger.i(tag, "Download already active as ${existing.id}; coalescing duplicate request")
            return existing.id
        }

        val jobId = UUID.randomUUID().toString()

        // Output container policy:
        // - placeholder jobs store "" so the worker adopts the JIT-resolved format;
        // - explicit user choices are accepted only when actually producible for
        //   the selected stream (never relabel WebM/Opus bytes as MP4/MP3);
        // - otherwise use the codec-derived recommended container.
        val requestedContainer = container
            ?.takeIf { it.isNotBlank() && !it.equals("Default", ignoreCase = true) }
        // Force-H.264 is applied here for non-placeholder jobs (and again after
        // JIT resolution in the worker). HDR formats are never re-encoded.
        val forceH264 = settingsRepository.forceH264Transcode.first() &&
            selectedFormat.requiresMuxing &&
            !selectedFormat.isHdr
        val finalContainer = when {
            selectedFormat.isPlaceholder -> ""
            forceH264 -> MediaContainer.MP4.extension.uppercase()
            requestedContainer != null && MediaContainer.isCompatible(selectedFormat, requestedContainer) ->
                MediaContainer.extensionOf(requestedContainer).uppercase()
            else -> MediaContainer.recommendedFor(selectedFormat).extension.uppercase()
        }

        val title = customTitle ?: mediaItem.title
        val author = customAuthor ?: mediaItem.author

        // Destructive replace: starting the same download again discards a
        // lingering FAILED record for it — no X-to-remove round-trip needed.
        jobRepository.discardFailedJob(mediaItem.sourceUrl, mediaItem.carouselIndex ?: 0)

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
            formatId = selectedFormat.id,
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
            muxStrategy = if (forceH264) {
                MuxStrategy.FORCE_H264.name
            } else {
                selectedFormat.muxStrategy?.name
            },
            videoChunkSize = selectedFormat.httpChunkSizeBytes ?: 0L,
            audioChunkSize = pairedAudio?.httpChunkSizeBytes ?: 0L,
            isPlaceholder = selectedFormat.isPlaceholder,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        jobRepository.insertJob(job)

        // Wi-Fi-only is evaluated at enqueue time and encoded as a WorkManager
        // constraint, so the OS will not start the job on metered data.
        val wifiOnly = settingsRepository.wifiOnlyDownloads.first()
        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_JOB_ID to jobId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                    )
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
     * Startup reconciliation (kill-on-fail across restarts):
     * 1. Every non-terminal Room job is killed unconditionally — its WorkManager
     *    work is cancelled and it becomes a FAILED(INTERRUPTED) card, immediately,
     *    regardless of connectivity or whether WorkManager rescheduled the work.
     *    A pending (IS_PENDING=1) MediaStore row from an interrupted export is swept.
     * 2. Orphan scratch dirs (`cache/downloads/<id>` with no live job) are swept.
     */
    private suspend fun reconcileOnStartup() = withContext(Dispatchers.IO) {
        try {
            val workManager = WorkManager.getInstance(context)
            val jobs = jobRepository.getActiveJobs()
            jobs.forEach { job ->
                Logger.i(tag, "Reconciling dead job ${job.id} (${job.status}) as FAILED(INTERRUPTED)")
                workManager.cancelAllWorkByTag(job.id)
                // P1-3 sweep: a MediaStore row created with IS_PENDING=1 during an
                // interrupted export is deleted if still pending.
                job.pendingUri?.let { uri ->
                    if (deleteIfStillPending(uri)) {
                        Logger.i(tag, "Swept orphaned pending MediaStore row for job ${job.id}")
                    }
                }
                jobRepository.markInterruptedIfNotFailed(job.id)
            }

            // No-resume + kill-everything-at-restart: every non-terminal row above
            // just became FAILED, so NO scratch dir belongs to live work anymore.
            // Partial files of force-stopped downloads are swept here too.
            File(context.cacheDir, "downloads").listFiles()?.forEach { dir ->
                Logger.i(tag, "Sweeping orphan scratch dir ${dir.name}")
                dir.deleteRecursively()
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
