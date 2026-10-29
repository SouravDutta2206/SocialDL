package com.socialdl.src.core.download

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.common.util.MediaStorageUtils
import com.socialdl.src.core.database.entity.DownloadJobEntity
import com.socialdl.src.core.ffmpeg.FFmpegEvent
import com.socialdl.src.core.ffmpeg.FFmpegProcessExecutor
import com.socialdl.src.core.model.DownloadStatus
import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MediaContainer
import com.socialdl.src.core.model.MuxStrategy
import com.socialdl.src.core.settings.SettingsRepository
import com.socialdl.src.extraction.api.ExtractionResult
import com.socialdl.src.extraction.api.ExtractorRegistry
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
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
    private val mediaStoreExporter: MediaStoreExporter,
    private val ffmpegProcessExecutor: FFmpegProcessExecutor,
    private val jobRepository: DownloadJobRepository,
    private val eventBus: DownloadEventBus,
    private val concurrencyGate: DownloadConcurrencyGate,
    private val extractorRegistry: ExtractorRegistry,
    private val notificationManager: DownloadNotificationManager,
    private val settingsRepository: SettingsRepository,
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
        val formatKind: FormatKind,
        val formatLabel: String,
        val formatId: String,
    )

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

        return exportAndPersist(job, finalFile, resolved)
    }

    /**
     * Effective stream configuration after optionally JIT-resolving placeholder
     * formats (batch/playlist items that defer extraction to download time).
     * Writes the initial DOWNLOADING(0f) progress row; marks FAILED and returns
     * null on any resolution failure (the caller stops without further work).
     *
     * Invariant: a JIT-resolved stream always has the job's requested
     * [FormatKind]; an unparseable stored kind fails as
     * [DownloadError.InvalidFormatKind], and a missing resolved kind fails as
     * [DownloadError.FormatUnavailable] instead of substituting another kind.
     */
    private suspend fun resolveEffectiveFormats(job: DownloadJobEntity): ResolvedStreams? {
        val jobId = job.id
        Logger.i(
            tag,
            "[$jobId] Acquiring streams: container=${job.container}, requiresMuxing=${job.requiresMuxing}, isPlaceholder=${job.isPlaceholder}"
        )

        // Fail closed on corrupt/legacy kind metadata: FormatKind.from() defaults
        // to VIDEO, which could turn an audio job into a video export. The enqueue
        // path always writes a valid kind, so this only affects damaged rows.
        val requestedKind = FormatSelector.requestedKindOrNull(job.formatKind)
        if (requestedKind == null) {
            Logger.e(
                tag,
                "[$jobId] Stored formatKind '${job.formatKind}' is missing or invalid for " +
                    "${job.sourceUrl} — failing closed"
            )
            markFailed(jobId, DownloadError.InvalidFormatKind)
            return null
        }

        // The first transition doubles as the "still active" guard: a row already
        // FAILED by startup reconciliation, or deleted by cancel, matches zero
        // rows and the pipeline must not start downloading for it.
        val started = jobRepository.registerProgress(
            id = jobId,
            status = DownloadStatus.DOWNLOADING,
            progress = 0f,
            downloadedBytes = 0L,
            totalBytes = job.totalBytes
        )
        if (!started) {
            Logger.i(tag, "[$jobId] Job is no longer active at start; aborting before download")
            return null
        }

        var effectiveDirectUrl = job.directUrl
        var effectiveAudioDirectUrl = job.audioDirectUrl
        var effectiveRequiresMuxing = job.requiresMuxing
        var effectiveVideoChunkSize = job.videoChunkSize
        var effectiveAudioChunkSize = job.audioChunkSize
        var effectiveMuxStrategy = job.muxStrategy?.let { strategy ->
            // Unparseable stored value must NOT silently fall through to a null
            // strategy: buildMuxArgs(null) transcodes audio instead of copying.
            // REMUX_COPY (c:v copy) is the closest-to-intent recovery.
            runCatching { MuxStrategy.valueOf(strategy) }
                .getOrElse {
                    Logger.w(tag, "[$jobId] Unparseable stored muxStrategy '$strategy' — defaulting to REMUX_COPY")
                    MuxStrategy.REMUX_COPY
                }
        }
        var effectiveContainer = job.container
        var effectiveFormatKind = requestedKind
        var effectiveFormatLabel = job.formatLabel
        var effectiveFormatId = job.formatId
        var resolvedViaExtraction = false
        val forceH264 = runCatching { settingsRepository.forceH264Transcode.first() }
            .getOrDefault(false)

        // Resolve when the job is a placeholder, its signed URL expired, or a
        // legacy row has no container to derive an extension/MIME from.
        if (job.isPlaceholder || effectiveDirectUrl.isNullOrBlank() || effectiveContainer.isBlank()) {
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

                    // Refresh the stream within the requested kind only: an audio
                    // placeholder must never become a video download (or vice versa).
                    // Prefer the exact persisted format id (same stream the user
                    // picked) over "first of kind", which may silently upgrade or
                    // downgrade the selection after a URL refresh.
                    val resolvedFormat = FormatSelector.select(
                        item = resolvedItem,
                        requestedKind = requestedKind,
                        exactFormatId = job.formatId,
                        allowExact = !job.isPlaceholder,
                    )

                    // Defensive invariant: selection must stay in the requested kind
                    // and yield a direct URL before any bytes are downloaded.
                    if (resolvedFormat == null ||
                        !FormatSelector.isUsable(resolvedFormat, requestedKind)
                    ) {
                        Logger.e(
                            tag,
                            "[$jobId] Format unavailable for ${job.sourceUrl}: " +
                                "requested=$requestedKind, resolvedId=${resolvedFormat?.id}, " +
                                "resolvedKind=${resolvedFormat?.kind}, " +
                                "image=${resolvedItem.imageFormats.size}, " +
                                "video=${resolvedItem.videoFormats.size}, " +
                                "audio=${resolvedItem.audioFormats.size}, " +
                                "exactFormatId=${job.formatId}"
                        )
                        markFailed(jobId, DownloadError.FormatUnavailable)
                        return null
                    }

                    effectiveDirectUrl = resolvedFormat.directUrl
                    effectiveRequiresMuxing = resolvedFormat.requiresMuxing
                    effectiveVideoChunkSize = resolvedFormat.httpChunkSizeBytes ?: 0L
                    effectiveMuxStrategy = resolvedFormat.muxStrategy
                    effectiveFormatKind = resolvedFormat.kind
                    effectiveFormatLabel = resolvedFormat.label
                    effectiveFormatId = resolvedFormat.id

                    // Only accept an explicit container when it is actually
                    // producible for this stream; otherwise adopt the codec-derived
                    // container instead of relabeling bytes (P0-5/P0-6).
                    val autoContainer = MediaContainer.recommendedFor(resolvedFormat)
                    val requestedContainer = job.container
                        .takeIf { it.isNotBlank() && !it.equals("Default", ignoreCase = true) }
                    effectiveContainer = requestedContainer
                        ?.takeIf { MediaContainer.isCompatible(resolvedFormat, it) }
                        ?.let { MediaContainer.extensionOf(it) }
                        ?: autoContainer.extension
                    effectiveContainer = effectiveContainer.uppercase(Locale.ROOT)

                    // Force-H.264 applies only to muxed, non-HDR streams: a full
                    // re-encode of HDR would destroy the dynamic range.
                    if (forceH264 && resolvedFormat.requiresMuxing && !resolvedFormat.isHdr) {
                        effectiveContainer = MediaContainer.MP4.extension.uppercase(Locale.ROOT)
                        effectiveMuxStrategy = MuxStrategy.FORCE_H264
                    }

                    if (resolvedFormat.requiresMuxing) {
                        val pairedAudio = resolvedItem.audioFormats.find { it.id == resolvedFormat.pairedAudioFormatId }
                            ?: resolvedItem.audioFormats.firstOrNull()
                        effectiveAudioDirectUrl = pairedAudio?.directUrl
                        effectiveAudioChunkSize = pairedAudio?.httpChunkSizeBytes ?: 0L
                    }
                    resolvedViaExtraction = true
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

        // Persist resolved metadata with a targeted UPDATE so export derives
        // extension/MIME/RELATIVE_PATH from the stream actually downloaded. A zero
        // row count means the user cancelled while extraction was running.
        if (resolvedViaExtraction) {
            val persisted = jobRepository.updateResolvedMetadata(
                id = jobId,
                container = effectiveContainer,
                formatId = effectiveFormatId,
                formatKind = effectiveFormatKind,
                formatLabel = effectiveFormatLabel
            )
            if (!persisted) return null
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
            // Non-mux jobs legitimately have no audio URL (DownloadManager stores
            // null); the mux path is already gated above on requiresMuxing having
            // a non-blank audio URL, so orEmpty() can never be observed as a URL.
            audioDirectUrl = effectiveAudioDirectUrl.orEmpty(),
            requiresMuxing = effectiveRequiresMuxing,
            videoChunkSize = effectiveVideoChunkSize,
            audioChunkSize = effectiveAudioChunkSize,
            muxStrategy = effectiveMuxStrategy,
            container = effectiveContainer,
            formatKind = effectiveFormatKind,
            formatLabel = effectiveFormatLabel,
            formatId = effectiveFormatId
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
     * the export step, right after the pending row is created, and again before
     * publish; a cancel that wins after publish compensates by deleting the file.
     * [resolved] carries the container actually downloaded (JIT-resolved
     * placeholders may differ from the stale [DownloadJobEntity.container]).
     * Returns true on success; false (FAILED written) on export failure.
     */
    private suspend fun exportAndPersist(
        job: DownloadJobEntity,
        finalFile: File,
        resolved: ResolvedStreams
    ): Boolean {
        val jobId = job.id

        // Guard read #1: row gone => user cancelled mid-pipeline; abort silently.
        if (jobRepository.getJob(jobId) == null) {
            Logger.i(tag, "[$jobId] Job row missing before export — cancelled; aborting")
            return false
        }

        Logger.i(tag, "[$jobId] Exporting ${finalFile.length()} bytes to MediaStore...")
        val exporting = jobRepository.registerProgress(
            id = jobId,
            status = DownloadStatus.EXPORTING,
            progress = 0.95f,
            downloadedBytes = finalFile.length(),
            totalBytes = finalFile.length()
        )
        if (!exporting) {
            Logger.i(tag, "[$jobId] Job became terminal before export; aborting")
            return false
        }

        val resolvedJob = job.copy(
            container = resolved.container,
            formatId = resolved.formatId,
            formatKind = resolved.formatKind.name,
            formatLabel = resolved.formatLabel
        )

        val outputUri = mediaStoreExporter.export(
            file = finalFile,
            title = resolvedJob.title,
            author = resolvedJob.author,
            container = resolved.container,
            kind = resolved.formatKind,
            onPendingRowCreated = { pendingUri ->
                // P1-3: record the pending MediaStore row durably, and re-check the
                // cancellation guard right after row creation so cancels mid-copy
                // abort before the (long) file copy starts.
                val persisted = jobRepository.setPendingUri(jobId, pendingUri.toString())
                if (!persisted) {
                    Logger.i(tag, "[$jobId] Job row missing after pending-row creation — cancelled")
                    return@export false
                }
                true
            },
            onBeforePublish = {
                // Phase 5 (#5): copy and publish are separate steps. Publishing is
                // the last point at which a cancel can still delete the file
                // instead of leaving a visible row with no history record, so
                // re-read the job row here and refuse to publish a dead job.
                val current = jobRepository.getJob(jobId)
                val stillExporting = current != null && current.status == DownloadStatus.EXPORTING
                if (!stillExporting) {
                    Logger.i(
                        tag,
                        "[$jobId] Job is no longer active before publish " +
                            "(status=${current?.status}); discarding the export"
                    )
                }
                stillExporting
            }
        )

        if (outputUri == null) {
            // A cancel deletes the job row; the exporter then deletes the row it
            // created and reports null. That is a cancellation, not an export
            // failure, and a deleted row must never be marked FAILED.
            if (jobRepository.getJob(jobId) == null) {
                Logger.i(tag, "[$jobId] Job row missing during export — cancelled; nothing published")
                return false
            }
            Logger.e(tag, "[$jobId] Failed to write file to MediaStore")
            markFailed(jobId, DownloadError.ExportFailed)
            return false
        }

        // The file is published from here on. Any cancel that wins before the
        // history transaction commits must delete exactly this export's URI, so a
        // cancelled job leaves neither a visible file nor a history row.
        var historyRecorded = false
        try {
            // Guard read #2: after the export, before the history transaction.
            if (jobRepository.getJob(jobId) == null) {
                Logger.i(tag, "[$jobId] Job row missing after export — cancelled; deleting the published file")
                deletePublishedExport(jobId, outputUri)
                return false
            }

            // Persist to Room: delete the EXPORTING row + insert history atomically
            // (§10). A zero-row delete means a cancel won the race; do not record a
            // completion for it.
            historyRecorded = jobRepository.finishJobSuccessfully(
                job = resolvedJob,
                outputUri = outputUri.toString(),
                fileSizeBytes = finalFile.length()
            )
            if (!historyRecorded) {
                Logger.i(tag, "[$jobId] Cancelled before the history insert; deleting the published file")
                deletePublishedExport(jobId, outputUri)
                return false
            }

            notificationManager.notifyCompleted(jobId, resolvedJob.title)
            Logger.i(tag, "[$jobId] Download completed successfully: uri=$outputUri, size=${finalFile.length()}")
            eventBus.emit(
                DownloadEvent.Completed(
                    jobId = jobId,
                    title = resolvedJob.title,
                    outputUri = outputUri.toString(),
                    fileSizeBytes = finalFile.length()
                )
            )
            return true
        } catch (e: CancellationException) {
            // Cancelled while the history transaction (or the completion signal)
            // was in flight. Compensate only when history was NOT written: deleting
            // the file after a committed history row would leave that row dangling.
            if (!historyRecorded) {
                Logger.i(tag, "[$jobId] Export cancelled before history committed; deleting the published file")
                deletePublishedExport(jobId, outputUri)
            }
            throw e
        }
    }

    /**
     * Phase 5 (#5) compensation: deletes exactly the MediaStore row this export
     * created — never another job's URI and never a user file. Runs in a
     * [NonCancellable] context because a cancellation is precisely when it is
     * needed; the resolver delete itself is blocking, so no suspension point can be
     * cancelled out from under it.
     */
    private suspend fun deletePublishedExport(jobId: String, outputUri: Uri) {
        withContext(NonCancellable) {
            val deleted = MediaStorageUtils.deleteMediaFromStorage(appContext, outputUri.toString())
            if (deleted) {
                Logger.i(tag, "[$jobId] Deleted exported file $outputUri (no history record written)")
            } else {
                Logger.w(
                    tag,
                    "[$jobId] Exported file $outputUri was already gone or could not be deleted; " +
                        "no history record was written"
                )
            }
        }
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
        // -nostdin prevents ffmpeg from consuming stdin; explicit -map picks the
        // first video of input 0 and the first audio of input 1, so a stream with
        // an unexpected extra track cannot be muxed by accident.
        val inputs = listOf(
            "-y",
            "-nostdin",
            "-i", videoFile.absolutePath,
            "-i", audioFile.absolutePath,
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-shortest"
        )
        val codecArgs = when (container.uppercase(Locale.ROOT)) {
            "MP4", "MOV" -> when (muxStrategy) {
                MuxStrategy.REMUX_COPY -> listOf("-c", "copy", "-movflags", "+faststart")
                MuxStrategy.FORCE_H264 -> listOf(
                    "-c:v", "libx264",
                    "-preset", "fast",
                    "-crf", "23",
                    "-c:a", "aac",
                    "-b:a", "192k",
                    "-movflags", "+faststart"
                )
                MuxStrategy.TRANSCODE_AUDIO_ONLY, null -> listOf(
                    "-c:v", "copy",
                    "-c:a", "aac",
                    "-b:a", "192k",
                    "-movflags", "+faststart"
                )
            }
            "WEBM" -> listOf("-c:v", "copy", "-c:a", "libopus", "-b:a", "160k")
            // MKV (and the defensive fallback) copies any codec pair losslessly.
            else -> listOf("-c", "copy")
        }
        return inputs + codecArgs + outputFile.absolutePath
    }
}
