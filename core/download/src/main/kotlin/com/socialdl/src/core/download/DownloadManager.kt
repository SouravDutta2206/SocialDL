package com.socialdl.src.core.download

import android.content.Context
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val workScheduler: DownloadWorkScheduler,
) {
    private val tag = "DownloadManager"
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Logger.e(tag, "Uncaught error in DownloadManager scope", e)
        }
    )

    /**
     * Phase 3 (finding #3) enqueue serialization.
     *
     * The Room transaction in [DownloadJobRepository.createJobIfNoActiveDuplicate]
     * makes the duplicate-check-and-insert atomic at the DB layer. This mutex
     * additionally covers the non-transactional WorkManager enqueue window: a
     * caller that coalesces onto another caller's job can only return that id
     * AFTER the winner's work request has been handed to WorkManager.
     *
     * Held only across "check-and-create row + enqueue work" — never across the
     * settings reads or entity construction above it.
     */
    private val enqueueMutex = Mutex()

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
     *
     * Coalescing contract (Phase 3 / finding #3) — exactly one active job and one
     * WorkRequest per `(sourceUrl, carouselIndex, formatId)` identity:
     * 1. Settings-derived fields and the entity are built BEFORE the lock, so a
     *    slow DataStore read never serializes unrelated enqueues.
     * 2. Under [enqueueMutex], the duplicate check and insert happen in one Room
     *    transaction ([DownloadJobRepository.createJobIfNoActiveDuplicate]) and
     *    the winner enqueues its WorkRequest before the lock is released.
     * 3. A caller that coalesces onto an existing job returns that job's id and
     *    enqueues nothing. Because the loser can only reach the check after the
     *    winner's enqueue finished, the returned id always has live work behind it.
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

        // Wi-Fi-only is evaluated at enqueue time and encoded as a WorkManager
        // constraint, so the OS will not start the job on metered data.
        val wifiOnly = settingsRepository.wifiOnlyDownloads.first()

        return enqueueMutex.withLock {
            // The transaction is authoritative: it also performs the destructive
            // replace of a lingering FAILED record for this source/carousel slot.
            val stored = jobRepository.createJobIfNoActiveDuplicate(job)
            if (stored.id != jobId) {
                Logger.i(tag, "Download already active as ${stored.id}; coalescing duplicate request")
                return@withLock stored.id
            }

            try {
                workScheduler.enqueue(jobId, wifiOnly)
            } catch (e: CancellationException) {
                // The caller was cancelled while WorkManager was persisting the
                // request. The rollback must survive that cancellation: a QUEUED row
                // whose worker may never exist would swallow the next tap as a
                // duplicate. If the request did get persisted, the orphaned work
                // aborts on its missing row (DownloadWorker's guard read).
                withContext(NonCancellable) {
                    Logger.i(tag, "Enqueue cancelled for job $jobId; rolling back the queued row")
                    jobRepository.deleteJob(jobId)
                    runCatching { workScheduler.cancel(jobId) }
                }
                throw e
            } catch (e: Exception) {
                // Do not leave a QUEUED zombie row: delete it and rethrow so callers
                // (batch counting) report a truthful enqueued count. NonCancellable so
                // a cancellation racing this failure cannot skip the cleanup.
                withContext(NonCancellable) {
                    Logger.e(tag, "WorkManager enqueue failed for job $jobId; deleting row", e)
                    jobRepository.deleteJob(jobId)
                }
                throw e
            }
            jobId
        }
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
        workScheduler.cancel(jobId)
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
            val jobs = jobRepository.getActiveJobs()
            jobs.forEach { job ->
                Logger.i(tag, "Reconciling dead job ${job.id} (${job.status}) as FAILED(INTERRUPTED)")
                workScheduler.cancel(job.id)
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
