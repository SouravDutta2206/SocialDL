package com.socialdl.src.core.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.database.entity.DownloadJobEntity
import com.socialdl.src.core.ffmpeg.FFmpegEvent
import com.socialdl.src.core.ffmpeg.FFmpegProcessExecutor
import com.socialdl.src.core.model.DownloadStatus
import com.socialdl.src.core.model.MuxStrategy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * The download work unit (§9). Reads its ENTIRE config from the Room job row via
 * [DownloadJobRepository] (WorkManager Data carries only KEY_JOB_ID — P0-4), and
 * persists every lifecycle transition + progress tick straight to Room. UI flows
 * observe Room; this worker emits no lifecycle events (only the Completed toast
 * signal).
 *
 * Failure semantics (kill-on-fail): any terminal failure invokes
 * [DownloadJobRepository.markFailed] with a typed [DownloadError] and returns
 * [Result.failure()]; the OneTimeWorkRequest dies fully — nothing schedulable
 * remains, no retry, no re-enqueue. The FAILED card is purely a user-side visual
 * record (the background job no longer exists).
 *
 * App restart (force-stop, swipe-away, crash, process death): ALL non-terminal
 * jobs fail immediately. WorkManager rescheduling of unfinished WorkSpecs is
 * rejected via the runAttemptCount guard in [doWork], and
 * [DownloadManager.reconcileOnStartup] cancels + fails everything left over.
 * Nothing ever re-runs or restarts from byte 0.
 *
 * Instant-cancellation guard reads: before export, right after the pending
 * MediaStore row is created, and again before publish (IS_PENDING=0). If the user
 * cancelled (row deleted), the current step aborts and `finally` cleans scratch.
 * Cancelling *during* the copy deletes the row that export created and rethrows,
 * and a cancel that wins after publish but before the history transaction
 * compensates by deleting the just-published file — a cancelled job never leaves a
 * visible file without a history record. Residual window: process death between
 * IS_PENDING=0 and the history commit, which startup reconciliation cannot sweep
 * (the row is no longer pending); publishing is therefore as late as possible.
 * Never a zombie UI card, never a retry.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val chunkedDownloader: ChunkedDownloader,
    private val ffmpegProcessExecutor: FFmpegProcessExecutor,
    private val jobRepository: DownloadJobRepository,
    private val concurrencyGate: DownloadConcurrencyGate,
    private val notificationManager: DownloadNotificationManager,
    private val streamResolver: StreamResolver,
    private val outputExporter: OutputExporter,
) : CoroutineWorker(appContext, workerParams) {

    private val tag = "DownloadWorker"

    /** Aborts in-flight OkHttp calls instantly on network loss (kill-on-fail §9). */
    private val networkCanceller = DownloadCallCanceller()

    /** Foreground-service notification promotion state (P1-1). */
    private var isForeground = false
    private var lastNotifiedPercent = -1

    companion object {
        /** The ONLY work input: everything else lives in the Room job row. */
        const val KEY_JOB_ID = "job_id"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val jobId = inputData.getString(KEY_JOB_ID).orEmpty()
        val job = runCatching { jobRepository.getJob(jobId) }.getOrNull()
        return notificationManager.buildForegroundInfo(
            jobId = jobId,
            title = job?.title ?: "Downloading media",
            status = DownloadStatus.DOWNLOADING,
            progress = 0f,
            downloadedBytes = 0L,
            totalBytes = job?.totalBytes
        )
    }

    /**
     * Promotes the download to a foreground dataSync notification early in the
     * pipeline so work continues while the app is backgrounded (P1-1). The
     * notification carries a Cancel action using the same instant-deletion
     * semantics as the UI. All FGS errors are non-fatal: a failed promotion
     * must never kill the download.
     */
    private suspend fun promoteToForeground(job: DownloadJobEntity) {
        try {
            setForeground(
                notificationManager.buildForegroundInfo(
                    jobId = job.id,
                    title = job.title,
                    status = DownloadStatus.DOWNLOADING,
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = job.totalBytes
                )
            )
            isForeground = true
        } catch (e: Exception) {
            Logger.w(tag, "[${job.id}] Foreground promotion failed (continuing in background)", e)
        }
    }

    /**
     * Updates the foreground notification on progress ticks when the integer
     * percent changes, and on phase transitions. Throttled by percent to keep
     * notification churn sane under high-frequency Room ticks.
     */
    private suspend fun maybeRefreshForeground(jobId: String) {
        if (!isForeground) return
        val job = jobRepository.getJob(jobId) ?: return
        val percent = (job.progress.coerceIn(0f, 1f) * 100).toInt()
        if (percent == lastNotifiedPercent && job.status == DownloadStatus.DOWNLOADING) return
        lastNotifiedPercent = percent
        try {
            setForeground(
                notificationManager.buildForegroundInfo(
                    jobId = job.id,
                    title = job.title,
                    status = job.status,
                    progress = job.progress,
                    downloadedBytes = job.downloadedBytes,
                    totalBytes = job.totalBytes
                )
            )
        } catch (e: Exception) {
            Logger.w(tag, "[$jobId] Foreground notification refresh failed", e)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return@withContext Result.failure()

        val job = jobRepository.getJob(jobId)
        if (job == null) {
            // Row deleted before/at start => the user already cancelled; nothing to do.
            Logger.i(tag, "[$jobId] Job row missing at worker start - cancelled; aborting")
            return@withContext Result.failure()
        }

        // A FAILED row can still have live WorkManager work (startup reconciliation
        // races, a late failure write). Never download for a terminal card.
        if (job.status == DownloadStatus.FAILED || job.status == DownloadStatus.COMPLETED) {
            Logger.i(tag, "[$jobId] Job is already terminal (${job.status}); refusing to run")
            return@withContext Result.failure()
        }

        // Kill-on-fail across restarts (§9): runAttemptCount > 0 is only possible
        // when WorkManager re-runs a WorkSpec after process death / force-stop.
        // Interrupted work is DEAD, never restarted — fail it immediately so no
        // partial download ever silently restarts from byte 0.
        if (runAttemptCount > 0) {
            Logger.i(tag, "[$jobId] Worker re-run after process death (attempt $runAttemptCount) — failing as interrupted")
            jobRepository.markFailed(jobId, DownloadError.Interrupted)
            return@withContext Result.failure()
        }

        val scratchDir = File(appContext.cacheDir, "downloads/$jobId")
        scratchDir.mkdirs()

        // Watch the network for the whole job: when the last usable network drops,
        // abort every in-flight call instantly (retries without a network are
        // pointless). During mux/export no call is registered — cancel is a no-op.
        val watchdog = NetworkLossWatchdog(appContext) { networkCanceller.cancel() }
        watchdog.register()

        try {
            val success = concurrencyGate.withDownloadPermit {
                runPipeline(job, scratchDir)
            }
            if (success) Result.success() else Result.failure()
        } catch (e: CancellationException) {
            Logger.i(tag, "[$jobId] Download task was cancelled")
            throw e
        } catch (e: Exception) {
            if (isStopped) {
                Logger.i(tag, "[$jobId] Download task was stopped")
                Result.failure()
            } else {
                val error = DownloadError.from(e)
                Logger.e(tag, "[$jobId] Download task failed with $error", e)
                markFailed(jobId, error)
                Result.failure()
            }
        } finally {
            watchdog.unregister()
            // Clean up task scratch directory (§9.1)
            scratchDir.deleteRecursively()
        }
    }

    /** Returns true when the job exported + persisted successfully; false otherwise (FAILED already written when applicable). */
    private suspend fun runPipeline(job: DownloadJobEntity, scratchDir: File): Boolean {
        promoteToForeground(job)
        val resolved = when (val outcome = streamResolver.resolve(job)) {
            is ResolveOutcome.Ready -> outcome.streams
            is ResolveOutcome.Failed -> {
                jobRepository.markFailed(job.id, outcome.error)
                return false
            }
            ResolveOutcome.Inactive -> return false
        }

        val finalFile = if (resolved.requiresMuxing) {
            val videoTemp = File(scratchDir, "video.tmp")
            val audioTemp = File(scratchDir, "audio.tmp")

            downloadPair(
                jobId = job.id,
                resolved = resolved,
                videoTemp = videoTemp,
                audioTemp = audioTemp,
                platform = job.platform
            )

            mux(
                jobId = job.id,
                scratchDir = scratchDir,
                container = resolved.container,
                muxStrategy = resolved.muxStrategy,
                videoTemp = videoTemp,
                audioTemp = audioTemp
            ) ?: return false // failure already written
        } else {
            val ext = resolved.container.lowercase(Locale.ROOT)
            val mediaTemp = File(scratchDir, "media.tmp")

            downloadSingle(
                jobId = job.id,
                url = resolved.directUrl,
                destination = mediaTemp,
                chunkSizeBytes = resolved.videoChunkSize,
                platform = job.platform
            )

            if (mediaTemp.length() == 0L) {
                Logger.e(tag, "[${job.id}] Single-stream download produced an empty file")
                markFailed(job.id, DownloadError.MissingStream)
                return false
            }

            val convertedFile = File(scratchDir, "output.$ext")
            if (mediaTemp.renameTo(convertedFile)) convertedFile else mediaTemp
        }

        return outputExporter.exportAndPersist(job, finalFile, resolved)
    }

    /**
     * Dual-stream download for YouTube video + audio muxing (§9), video first
     * (weighted 80% of progress) then audio (15%). Throws on IO failure —
     * handled by [doWork]'s catch, which maps to a typed [DownloadError].
     */
    private suspend fun downloadPair(
        jobId: String,
        resolved: ResolvedStreams,
        videoTemp: File,
        audioTemp: File,
        platform: String
    ) {
        Logger.d(tag, "[$jobId] Downloading video stream (chunkSize=${resolved.videoChunkSize})...")
        chunkedDownloader.download(
            url = resolved.directUrl,
            destination = videoTemp,
            chunkSizeBytes = resolved.videoChunkSize,
            platform = platform,
            isStopped = { isStopped },
            canceller = networkCanceller
        ) { bytesWritten, totalBytes ->
            val progress = if (totalBytes != null && totalBytes > 0) {
                (bytesWritten.toFloat() / totalBytes) * 0.8f // Video accounts for 80% of download progress
            } else {
                0.2f
            }
            registerProgress(jobId, DownloadStatus.DOWNLOADING, progress, bytesWritten, totalBytes)
        }

        Logger.d(tag, "[$jobId] Downloading audio stream (chunkSize=${resolved.audioChunkSize})...")
        chunkedDownloader.download(
            url = resolved.audioDirectUrl,
            destination = audioTemp,
            chunkSizeBytes = resolved.audioChunkSize,
            platform = platform,
            isStopped = { isStopped },
            canceller = networkCanceller
        ) { bytesWritten, totalBytes ->
            val progress = if (totalBytes != null && totalBytes > 0) {
                0.8f + (bytesWritten.toFloat() / totalBytes) * 0.15f // Audio accounts for 15%
            } else {
                0.9f
            }
            registerProgress(
                jobId,
                DownloadStatus.DOWNLOADING,
                progress,
                videoTemp.length() + bytesWritten,
                totalBytes?.let { it + videoTemp.length() }
            )
        }
    }

    /**
     * Single-stream download (progressive video, audio-only, non-YouTube).
     * Failure semantics: throws — handled by [doWork]'s catch.
     */
    private suspend fun downloadSingle(
        jobId: String,
        url: String,
        destination: File,
        chunkSizeBytes: Long,
        platform: String
    ) {
        Logger.d(tag, "[$jobId] Downloading single media stream (chunkSize=$chunkSizeBytes)...")
        chunkedDownloader.download(
            url = url,
            destination = destination,
            chunkSizeBytes = chunkSizeBytes,
            platform = platform,
            isStopped = { isStopped },
            canceller = networkCanceller
        ) { bytesWritten, totalBytes ->
            val progress = if (totalBytes != null && totalBytes > 0) {
                bytesWritten.toFloat() / totalBytes
            } else {
                0.5f
            }
            registerProgress(jobId, DownloadStatus.DOWNLOADING, progress, bytesWritten, totalBytes)
        }
    }

    /**
     * Runs the FFmpeg mux step over a downloaded video+audio pair (§2.4 / §9).
     * Persists the MUXING state; marks FAILED and returns null on failure.
     */
    private suspend fun mux(
        jobId: String,
        scratchDir: File,
        container: String,
        muxStrategy: MuxStrategy?,
        videoTemp: File,
        audioTemp: File
    ): File? {
        if (!ffmpegProcessExecutor.isAvailable()) {
            Logger.e(tag, "[$jobId] FFmpeg binary is unavailable; cannot mux")
            markFailed(jobId, DownloadError.MuxFailed)
            return null
        }

        Logger.i(tag, "[$jobId] Both streams downloaded. Starting FFmpeg muxing into $container...")
        val muxing = jobRepository.registerProgress(
            id = jobId,
            status = DownloadStatus.MUXING,
            progress = 0.95f,
            downloadedBytes = videoTemp.length() + audioTemp.length(),
            totalBytes = videoTemp.length() + audioTemp.length()
        )
        if (!muxing) {
            Logger.i(tag, "[$jobId] Job became terminal before muxing; aborting")
            return null
        }

        val ext = container.lowercase(Locale.ROOT)
        val muxedFile = File(scratchDir, "muxed.$ext")

        val ffmpegArgs = MuxArgs.build(
            videoFile = videoTemp,
            audioFile = audioTemp,
            outputFile = muxedFile,
            container = container,
            muxStrategy = muxStrategy
        )

        var muxSuccess = false
        var muxError = ""
        ffmpegProcessExecutor.run(ffmpegArgs).collect { event ->
            when (event) {
                is FFmpegEvent.Completed -> {
                    muxSuccess = true
                }
                is FFmpegEvent.Failed -> {
                    muxError = event.errorLog ?: "Exit code ${event.exitCode}"
                }
            }
        }

        if (!muxSuccess || !muxedFile.exists() || muxedFile.length() == 0L) {
            Logger.e(tag, "[$jobId] FFmpeg muxing failed: $muxError")
            markFailed(jobId, DownloadError.MuxFailed)
            return null
        }

        return muxedFile
    }

    private suspend fun markFailed(jobId: String, error: DownloadError) {
        jobRepository.markFailed(jobId, error)
    }

    private suspend fun registerProgress(
        jobId: String,
        status: DownloadStatus,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long?
    ) {
        jobRepository.registerProgress(
            id = jobId,
            status = status,
            progress = progress,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes
        )
        maybeRefreshForeground(jobId)
    }
}
