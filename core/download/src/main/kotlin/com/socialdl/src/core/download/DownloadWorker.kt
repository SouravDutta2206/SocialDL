package com.socialdl.src.core.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.database.entity.DownloadJobEntity
import com.socialdl.src.core.model.DownloadStatus
import com.socialdl.src.core.ffmpeg.FFmpegEvent
import com.socialdl.src.core.ffmpeg.FFmpegProcessExecutor
import com.socialdl.src.core.model.MuxStrategy
import com.socialdl.src.extraction.api.ExtractionResult
import com.socialdl.src.extraction.api.ExtractorRegistry
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
 * Instant-cancellation guard reads: before export and again right before the
 * history-insert, the job row is re-read. If the user cancelled (row deleted),
 * the current step aborts and `finally` cleans scratch. Cancellation landing
 * *during* the MediaStore copy may finish the export before the guard — the
 * accepted outcome is an exported file with no active card and no history
 * record. Never a zombie UI card, never a retry.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val chunkedDownloader: ChunkedDownloader,
    private val mediaStoreExporter: MediaStoreExporter,
    private val ffmpegProcessExecutor: FFmpegProcessExecutor,
    private val jobRepository: DownloadJobRepository,
    private val eventBus: DownloadEventBus,
    private val concurrencyGate: DownloadConcurrencyGate,
    private val extractorRegistry: ExtractorRegistry,
    private val notificationManager: DownloadNotificationManager,
) : CoroutineWorker(appContext, workerParams) {

    /** Effective stream configuration after optional JIT resolution of a placeholder format. */
    private data class ResolvedStreams(
        val directUrl: String,
        val audioDirectUrl: String,
        val requiresMuxing: Boolean,
        val videoChunkSize: Long,
        val audioChunkSize: Long,
        val muxStrategy: MuxStrategy?,
        val container: String,
    )

    private val tag = "DownloadWorker"

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
            Logger.i(tag, "[$jobId] Job row missing at worker start — cancelled; aborting")
            return@withContext Result.failure()
        }

        val scratchDir = File(appContext.cacheDir, "downloads/$jobId")
        scratchDir.mkdirs()

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
            // Clean up task scratch directory (§9.1)
            scratchDir.deleteRecursively()
        }
    }

    /** Returns true when the job exported + persisted successfully; false otherwise (FAILED already written when applicable). */
    private suspend fun runPipeline(job: DownloadJobEntity, scratchDir: File): Boolean {
        promoteToForeground(job)
        val resolved = resolveEffectiveFormats(job)
        if (resolved == null) return false // failure already written

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

        return exportAndPersist(job, finalFile)
    }

    /**
     * Effective stream configuration after optionally JIT-resolving placeholder
     * formats (batch/playlist items that defer extraction to download time).
     * Writes the initial DOWNLOADING(0f) progress row; marks FAILED and returns
     * null on any resolution failure (the caller stops without further work).
     */
    private suspend fun resolveEffectiveFormats(job: DownloadJobEntity): ResolvedStreams? {
        val jobId = job.id
        Logger.i(
            tag,
            "[$jobId] Acquiring streams: container=${job.container}, requiresMuxing=${job.requiresMuxing}, isPlaceholder=${job.isPlaceholder}"
        )
        jobRepository.registerProgress(
            id = jobId,
            status = DownloadStatus.DOWNLOADING,
            progress = 0f,
            downloadedBytes = 0L,
            totalBytes = job.totalBytes
        )

        var effectiveDirectUrl = job.directUrl
        var effectiveAudioDirectUrl = job.audioDirectUrl
        var effectiveRequiresMuxing = job.requiresMuxing
        var effectiveVideoChunkSize = job.videoChunkSize
        var effectiveAudioChunkSize = job.audioChunkSize
        var effectiveMuxStrategy = job.muxStrategy?.let { strategy ->
            runCatching { MuxStrategy.valueOf(strategy) }.getOrNull()
        }
        var effectiveContainer = job.container

        if (job.isPlaceholder || effectiveDirectUrl.isNullOrBlank()) {
            Logger.i(tag, "[$jobId] JIT resolving stream formats for: ${job.sourceUrl}")

            val extractor = extractorRegistry.resolve(job.sourceUrl)
            if (extractor == null) {
                Logger.e(tag, "[$jobId] No extractor available for ${job.sourceUrl}")
                markFailed(jobId, DownloadError.NoExtractor)
                return null
            }

            when (val result = extractor.extract(job.sourceUrl)) {
                is ExtractionResult.Success -> {
                    val resolvedItem = result.items.firstOrNull()
                    if (resolvedItem == null) {
                        Logger.e(tag, "[$jobId] No media item returned for ${job.sourceUrl}")
                        markFailed(jobId, DownloadError.ExtractionFailed)
                        return null
                    }

                    val isAudio = job.formatKind == "AUDIO"
                    val resolvedFormat = if (isAudio) {
                        resolvedItem.audioFormats.firstOrNull() ?: resolvedItem.videoFormats.firstOrNull()
                    } else {
                        resolvedItem.videoFormats.firstOrNull() ?: resolvedItem.audioFormats.firstOrNull()
                    }

                    if (resolvedFormat == null || resolvedFormat.directUrl.isNullOrBlank()) {
                        Logger.e(tag, "[$jobId] No direct stream URL resolved for ${job.sourceUrl}")
                        markFailed(jobId, DownloadError.MissingStream)
                        return null
                    }

                    effectiveDirectUrl = resolvedFormat.directUrl
                    effectiveRequiresMuxing = resolvedFormat.requiresMuxing
                    effectiveVideoChunkSize = resolvedFormat.httpChunkSizeBytes ?: 0L
                    effectiveMuxStrategy = resolvedFormat.muxStrategy
                    effectiveContainer =
                        if (job.container.isNotBlank() && job.container != "Default") job.container
                        else resolvedFormat.container

                    if (resolvedFormat.requiresMuxing) {
                        val pairedAudio = resolvedItem.audioFormats.find { it.id == resolvedFormat.pairedAudioFormatId }
                            ?: resolvedItem.audioFormats.firstOrNull()
                        effectiveAudioDirectUrl = pairedAudio?.directUrl
                        effectiveAudioChunkSize = pairedAudio?.httpChunkSizeBytes ?: 0L
                    }
                }
                is ExtractionResult.Error -> {
                    Logger.e(tag, "[$jobId] JIT stream resolution failed: ${result.cause.message}")
                    markFailed(jobId, DownloadError.ExtractionFailed)
                    return null
                }
            }
        }

        if (effectiveDirectUrl.isNullOrBlank()) {
            Logger.e(tag, "[$jobId] Missing direct download URL")
            markFailed(jobId, DownloadError.MissingStream)
            return null
        }

        // P0-3 gate: a mux-required format with no audio track must fail before any
        // bytes are downloaded — never silently fall through to a video-only export.
        if (effectiveRequiresMuxing && effectiveAudioDirectUrl.isNullOrBlank()) {
            Logger.e(tag, "[$jobId] Format requires muxing but no audio stream was resolved")
            markFailed(jobId, DownloadError.MissingAudioStream)
            return null
        }

        return ResolvedStreams(
            directUrl = effectiveDirectUrl,
            audioDirectUrl = effectiveAudioDirectUrl!!,
            requiresMuxing = effectiveRequiresMuxing,
            videoChunkSize = effectiveVideoChunkSize,
            audioChunkSize = effectiveAudioChunkSize,
            muxStrategy = effectiveMuxStrategy,
            container = effectiveContainer
        )
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
            isStopped = { isStopped }
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
            isStopped = { isStopped }
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
            isStopped = { isStopped }
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
        Logger.i(tag, "[$jobId] Both streams downloaded. Starting FFmpeg muxing into $container...")
        jobRepository.registerProgress(
            id = jobId,
            status = DownloadStatus.MUXING,
            progress = 0.95f,
            downloadedBytes = videoTemp.length() + audioTemp.length(),
            totalBytes = videoTemp.length() + audioTemp.length()
        )

        val ext = container.lowercase(Locale.ROOT)
        val muxedFile = File(scratchDir, "muxed.$ext")

        val ffmpegArgs = buildMuxArgs(
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
                is FFmpegEvent.Progress -> {
                    Logger.d(tag, "[$jobId] Mux progress: timeSeconds=${event.timeSeconds}")
                }
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

    /**
     * Exports [finalFile] to MediaStore / scoped storage (§9.7) and persists the
     * Room history row (§10). Guard reads against instant cancellation run before
     * the export step and again right before the success transaction.
     * Returns true on success; false (FAILED written) on export failure.
     */
    private suspend fun exportAndPersist(job: DownloadJobEntity, finalFile: File): Boolean {
        val jobId = job.id

        // Guard read #1: row gone => user cancelled mid-pipeline; abort silently.
        if (jobRepository.getJob(jobId) == null) {
            Logger.i(tag, "[$jobId] Job row missing before export — cancelled; aborting")
            return false
        }

        Logger.i(tag, "[$jobId] Exporting ${finalFile.length()} bytes to MediaStore...")
        jobRepository.registerProgress(
            id = jobId,
            status = DownloadStatus.EXPORTING,
            progress = 0.95f,
            downloadedBytes = finalFile.length(),
            totalBytes = finalFile.length()
        )

        val isAudioOnly = job.formatKind.equals("AUDIO", ignoreCase = true) ||
                job.container.uppercase(Locale.ROOT) in listOf("MP3", "M4A", "OPUS", "FLAC", "WAV", "AAC", "OGG") ||
                job.formatLabel.contains("MP3", ignoreCase = true) ||
                job.formatLabel.contains("M4A", ignoreCase = true) ||
                job.formatLabel.contains("OPUS", ignoreCase = true) ||
                job.formatLabel.contains("FLAC", ignoreCase = true)

        val outputUri = mediaStoreExporter.export(
            file = finalFile,
            title = job.title,
            author = job.author,
            container = job.container,
            isAudio = isAudioOnly
        )

        if (outputUri == null) {
            Logger.e(tag, "[$jobId] Failed to write file to MediaStore")
            markFailed(jobId, DownloadError.ExportFailed)
            return false
        }

        // Guard read #2: after the export, before the history transaction. A cancel
        // landing here leaves an exported file with no active card and no history
        // record (accepted micro-window; the user can delete it via gallery).
        if (jobRepository.getJob(jobId) == null) {
            Logger.i(tag, "[$jobId] Job row missing after export — cancelled; history skipped")
            return false
        }

        // Persist to Room: insert history + delete job atomically (§10). All terminal
        // outcomes are written before the Result is returned.
        jobRepository.finishJobSuccessfully(
            job = job,
            outputUri = outputUri.toString(),
            fileSizeBytes = finalFile.length()
        )

        Logger.i(tag, "[$jobId] Download completed successfully: uri=$outputUri, size=${finalFile.length()}")
        eventBus.emit(
            DownloadEvent.Completed(
                jobId = jobId,
                title = job.title,
                outputUri = outputUri.toString(),
                fileSizeBytes = finalFile.length()
            )
        )
        return true
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

    private fun buildMuxArgs(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        container: String,
        muxStrategy: MuxStrategy? = null
    ): List<String> {
        val upperContainer = container.uppercase(Locale.ROOT)
        return when (upperContainer) {
            "MP4" -> {
                when (muxStrategy) {
                    MuxStrategy.REMUX_COPY -> listOf(
                        "-y",
                        "-i", videoFile.absolutePath,
                        "-i", audioFile.absolutePath,
                        "-c", "copy",
                        "-movflags", "+faststart",
                        outputFile.absolutePath
                    )
                    MuxStrategy.FORCE_H264 -> listOf(
                        "-y",
                        "-i", videoFile.absolutePath,
                        "-i", audioFile.absolutePath,
                        "-c:v", "libx264",
                        "-preset", "fast",
                        "-crf", "23",
                        "-c:a", "aac",
                        "-b:a", "192k",
                        "-movflags", "+faststart",
                        outputFile.absolutePath
                    )
                    MuxStrategy.TRANSCODE_AUDIO_ONLY, null -> listOf(
                        "-y",
                        "-i", videoFile.absolutePath,
                        "-i", audioFile.absolutePath,
                        "-c:v", "copy",
                        "-c:a", "aac",
                        "-b:a", "192k",
                        "-movflags", "+faststart",
                        outputFile.absolutePath
                    )
                }
            }
            "MKV" -> listOf(
                "-y",
                "-i", videoFile.absolutePath,
                "-i", audioFile.absolutePath,
                "-c", "copy",
                outputFile.absolutePath
            )
            "WEBM" -> listOf(
                "-y",
                "-i", videoFile.absolutePath,
                "-i", audioFile.absolutePath,
                "-c:v", "copy",
                "-c:a", "libopus",
                outputFile.absolutePath
            )
            else -> listOf(
                "-y",
                "-i", videoFile.absolutePath,
                "-i", audioFile.absolutePath,
                "-c:v", "copy",
                "-c:a", "aac",
                "-movflags", "+faststart",
                outputFile.absolutePath
            )
        }
    }
}
