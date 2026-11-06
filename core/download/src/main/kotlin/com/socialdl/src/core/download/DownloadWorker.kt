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
import com.socialdl.src.core.ffmpeg.MediaProbe
import com.socialdl.src.core.model.DownloadStatus
import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MediaContainer
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
    private val coverArt: CoverArt,
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

        /**
         * Linux rejects a single argv element above ~128 KB, so a base64 picture block
         * larger than this is dropped instead of failing the whole conversion.
         */
        private const val MAX_PICTURE_BLOCK_CHARS = 100_000
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
                    totalBytes = job.totalBytes,
                    formatKind = FormatKind.fromOrNull(job.formatKind)
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

        // A media CDN 401/403 ("server refused access") is not necessarily final:
        // the signed URL can be refused while the same post downloads fine from a
        // freshly minted one (observed on YouTube playlist batches, where one item
        // fails and the identical video succeeds on the next attempt). yt-dlp
        // re-extracts in that situation; do the same, preferring a different stream
        // for a placeholder. Platforms that can resolve public content anonymously
        // get one extra attempt without the saved session, because a refused URL is
        // usually bound to that session.
        val anonymousRetry = streamResolver.supportsAnonymousRetry(job.sourceUrl)
        val maxAttempts = if (anonymousRetry) 3 else 2
        var attempt = 0
        var rejectedFormatId: String? = null
        var rejectedUrl: String? = null

        while (true) {
            // 0 = normal, 1 = anonymous (when available), 2 = session-bound refresh.
            val useSavedSession = !(anonymousRetry && attempt == 1)
            val resolved = when (
                val outcome = streamResolver.resolve(
                    job = job,
                    forceRefresh = attempt > 0,
                    useSavedSession = useSavedSession,
                    rejectedFormatId = rejectedFormatId,
                    rejectedUrl = rejectedUrl,
                )
            ) {
                is ResolveOutcome.Ready -> outcome.streams
                is ResolveOutcome.Failed -> {
                    // An anonymous re-resolution can legitimately fail for gated
                    // content (member-only, age-restricted) even though the
                    // session-bound one works. Fall back to the remaining attempt
                    // instead of failing the job here.
                    if (!useSavedSession && attempt + 1 < maxAttempts) {
                        Logger.w(
                            tag,
                            "[${job.id}] Anonymous re-resolution failed " +
                                "(${outcome.error}); falling back to the saved session"
                        )
                        attempt++
                        continue
                    }
                    jobRepository.markFailed(job.id, outcome.error)
                    return false
                }
                ResolveOutcome.Inactive -> return false
            }

            try {
                return downloadResolved(job, resolved, scratchDir)
            } catch (e: Exception) {
                if (e.isRefreshableRejection() && attempt + 1 < maxAttempts) {
                    Logger.w(
                        tag,
                        "[${job.id}] CDN refused the signed stream URL " +
                            "(host=${hostOf(resolved.directUrl)}, format=${resolved.formatId}, " +
                            "container=${resolved.container}, ${e.message}); " +
                            "refreshing (attempt ${attempt + 1}/$maxAttempts, " +
                            "savedSession=${!anonymousRetry || attempt + 1 != 1})"
                    )
                    rejectedFormatId = resolved.formatId
                    rejectedUrl = resolved.directUrl
                    attempt++
                    // A fresh attempt must not append to the refused attempt's bytes.
                    scratchDir.listFiles()?.forEach { it.deleteRecursively() }
                    continue
                }
                throw e
            }
        }
    }

    /**
     * True for the one failure a fresh URL can plausibly fix: the CDN answered
     * 401/403 to a stream URL. Every other transfer error keeps its existing
     * terminal semantics (retrying a 404 or a corrupt range is pointless).
     */
    private fun Exception.isRefreshableRejection(): Boolean =
        this is HttpStatusException && (code == 401 || code == 403)

    /** Host only — a signed CDN URL must never be written to logcat. */
    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull() ?: "<unknown>"

    /** Downloads, muxes/converts and exports the resolved streams. */
    private suspend fun downloadResolved(
        job: DownloadJobEntity,
        resolved: ResolvedStreams,
        scratchDir: File
    ): Boolean {
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

            // The probe tells the mux which streams can be copied into the chosen
            // container and which must be re-encoded to fit it.
            val videoProbe = ffmpegProcessExecutor.probe(videoTemp)
            val audioProbe = ffmpegProcessExecutor.probe(audioTemp)

            mux(
                jobId = job.id,
                scratchDir = scratchDir,
                container = resolved.container,
                muxStrategy = resolved.muxStrategy,
                videoCodec = videoProbe?.videoCodec,
                audioCodec = audioProbe?.audioCodec,
                audioBitrateKbps = audioProbe?.bitrateKbps,
                title = job.title,
                author = job.author,
                videoTemp = videoTemp,
                audioTemp = audioTemp
            ) ?: return false // failure already written
        } else {
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

            if (resolved.formatKind == FormatKind.IMAGE) {
                // Images have no conversion path: the bytes are already the target.
                val ext = resolved.container.lowercase(Locale.ROOT)
                val convertedFile = File(scratchDir, "output.$ext")
                if (mediaTemp.renameTo(convertedFile)) convertedFile else mediaTemp
            } else {
                // Audio and progressive video are re-containerised on demand; the
                // conversion step decides copy vs encode from the probed codecs.
                convert(job, mediaTemp, resolved, scratchDir) ?: return false
            }
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
        videoCodec: String?,
        audioCodec: String?,
        audioBitrateKbps: Int?,
        title: String?,
        author: String?,
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
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            audioBitrateKbps = audioBitrateKbps,
            muxStrategy = muxStrategy,
            title = title,
            author = author
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

    /**
     * Converts one downloaded stream into the job's chosen output container.
     *
     * A container whose codec matches the source codec is a stream copy; anything
     * else is encoded at the measured source bitrate. Title, artist, and cover art are
     * embedded when the container supports them. Persists the MUXING state, marks
     * FAILED with a typed error and returns null on failure.
     */
    private suspend fun convert(
        job: DownloadJobEntity,
        sourceTemp: File,
        resolved: ResolvedStreams,
        scratchDir: File
    ): File? {
        if (!ffmpegProcessExecutor.isAvailable()) {
            Logger.e(tag, "[${job.id}] FFmpeg binary is unavailable; cannot convert")
            markFailed(job.id, DownloadError.ConversionFailed)
            return null
        }

        val probe = ffmpegProcessExecutor.probe(sourceTemp)
        if (probe == null) {
            Logger.e(tag, "[${job.id}] Could not read the downloaded file's codec and bitrate")
            markFailed(job.id, DownloadError.ConversionFailed)
            return null
        }

        val target = MediaContainer.from(resolved.container)
        if (target == null) {
            Logger.e(tag, "[${job.id}] Unsupported output container '${resolved.container}'")
            markFailed(job.id, DownloadError.UnsupportedFormat)
            return null
        }

        val converting = jobRepository.registerProgress(
            id = job.id,
            status = DownloadStatus.MUXING,
            progress = 0.95f,
            downloadedBytes = sourceTemp.length(),
            totalBytes = sourceTemp.length()
        )
        if (!converting) {
            Logger.i(tag, "[${job.id}] Job became terminal before conversion; aborting")
            return null
        }

        val output = File(scratchDir, "output.${target.extension}")
        val args = if (resolved.formatKind == FormatKind.AUDIO) {
            buildAudioArgs(job, sourceTemp, output, target, probe, scratchDir)
        } else {
            buildVideoArgs(job, sourceTemp, output, target, probe)
        }
        if (args == null) {
            markFailed(job.id, DownloadError.ConversionFailed)
            return null
        }

        var success = false
        var conversionError = ""
        ffmpegProcessExecutor.run(args).collect { event ->
            when (event) {
                is FFmpegEvent.Completed -> success = true
                is FFmpegEvent.Failed -> conversionError = event.errorLog ?: "Exit code ${event.exitCode}"
            }
        }

        if (!success || !output.exists() || output.length() == 0L) {
            Logger.e(tag, "[${job.id}] Conversion to ${target.extension} failed: $conversionError")
            markFailed(job.id, DownloadError.ConversionFailed)
            return null
        }

        // The converted file replaces the downloaded source.
        sourceTemp.delete()
        return output
    }

    /** Audio argv, or null when the download has no audio stream to convert. */
    private suspend fun buildAudioArgs(
        job: DownloadJobEntity,
        source: File,
        output: File,
        target: MediaContainer,
        probe: MediaProbe,
        scratchDir: File,
    ): List<String>? {
        val audioCodec = probe.audioCodec ?: run {
            Logger.e(tag, "[${job.id}] Downloaded file has no audio stream to convert")
            return null
        }
        val cover = if (MediaContainer.supportsCoverArt(target)) {
            coverArt.prepare(job.thumbnailUrl, scratchDir)
        } else {
            null
        }
        val pictureBlock = if (target == MediaContainer.OPUS) pictureBlockFor(job, cover) else null
        Logger.i(
            tag,
            "[${job.id}] Converting $audioCodec (${probe.bitrateKbps}kbps) to " +
                "${target.extension} (cover=${cover != null})"
        )
        return ConvertArgs.build(
            source = source,
            output = output,
            target = target,
            sourceCodec = audioCodec,
            bitrateKbps = probe.bitrateKbps,
            title = job.title,
            artist = job.author,
            cover = cover,
            pictureBlock = pictureBlock,
        )
    }

    /** Video argv, or null when the download has no video stream to convert. */
    private fun buildVideoArgs(
        job: DownloadJobEntity,
        source: File,
        output: File,
        target: MediaContainer,
        probe: MediaProbe,
    ): List<String>? {
        val videoCodec = probe.videoCodec ?: run {
            Logger.e(tag, "[${job.id}] Downloaded file has no video stream to convert")
            return null
        }
        Logger.i(
            tag,
            "[${job.id}] Converting video=$videoCodec audio=${probe.audioCodec} to ${target.extension}"
        )
        return ConvertArgs.buildVideo(
            source = source,
            output = output,
            target = target,
            videoCodec = videoCodec,
            audioCodec = probe.audioCodec,
            audioBitrateKbps = probe.bitrateKbps,
            title = job.title,
            artist = job.author,
        )
    }

    /**
     * The Ogg container carries art as a Base64 comment. The block travels as one argv
     * element, so an oversized image is dropped rather than failing the process spawn
     * ("Argument list too long").
     */
    private fun pictureBlockFor(job: DownloadJobEntity, cover: File?): String? {
        val block = cover?.let { runCatching { ConvertArgs.pictureBlock(it.readBytes()) }.getOrNull() }
            ?: return null
        if (block.length > MAX_PICTURE_BLOCK_CHARS) {
            Logger.w(tag, "[${job.id}] Cover art too large for the Ogg metadata block; exporting without it")
            return null
        }
        return block
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
