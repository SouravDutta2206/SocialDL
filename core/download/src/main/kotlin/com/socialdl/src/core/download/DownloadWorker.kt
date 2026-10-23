package com.socialdl.src.core.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.database.dao.DownloadDao
import com.socialdl.src.core.database.entity.DownloadHistoryEntity
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

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val chunkedDownloader: ChunkedDownloader,
    private val mediaStoreExporter: MediaStoreExporter,
    private val ffmpegProcessExecutor: FFmpegProcessExecutor,
    private val downloadDao: DownloadDao,
    private val eventBus: DownloadEventBus,
    private val concurrencyGate: DownloadConcurrencyGate,
    private val extractorRegistry: ExtractorRegistry,
) : CoroutineWorker(appContext, workerParams) {

    private val tag = "DownloadWorker"

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_TITLE = "title"
        const val KEY_AUTHOR = "author"
        const val KEY_THUMBNAIL_URL = "thumbnail_url"
        const val KEY_PLATFORM = "platform"
        const val KEY_CONTAINER = "container"
        const val KEY_FORMAT_LABEL = "format_label"
        const val KEY_DIRECT_URL = "direct_url"
        const val KEY_REQUIRES_MUXING = "requires_muxing"
        const val KEY_AUDIO_DIRECT_URL = "audio_direct_url"
        const val KEY_AUDIO_CONTAINER = "audio_container"
        const val KEY_MUX_STRATEGY = "mux_strategy"
        const val KEY_HTTP_CHUNK_SIZE = "http_chunk_size"
        const val KEY_AUDIO_HTTP_CHUNK_SIZE = "audio_http_chunk_size"
        const val KEY_FORMAT_KIND = "format_kind"
        const val KEY_GROUP_ID = "group_id"
        const val KEY_IS_PLACEHOLDER = "is_placeholder"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return@withContext Result.failure()
        val sourceUrl = inputData.getString(KEY_SOURCE_URL) ?: ""
        val title = inputData.getString(KEY_TITLE) ?: "Media Download"
        val author = inputData.getString(KEY_AUTHOR)
        val thumbnailUrl = inputData.getString(KEY_THUMBNAIL_URL)
        val platform = inputData.getString(KEY_PLATFORM) ?: "UNKNOWN"
        val container = inputData.getString(KEY_CONTAINER) ?: "MP4"
        val formatLabel = inputData.getString(KEY_FORMAT_LABEL) ?: ""
        val formatKind = inputData.getString(KEY_FORMAT_KIND)
        val muxStrategyRaw = inputData.getString(KEY_MUX_STRATEGY)
        val muxStrategy = muxStrategyRaw?.takeIf { it.isNotBlank() }?.let {
            runCatching { MuxStrategy.valueOf(it) }.getOrNull()
        }
        val directUrl = inputData.getString(KEY_DIRECT_URL)
        val requiresMuxing = inputData.getBoolean(KEY_REQUIRES_MUXING, false)
        val audioDirectUrl = inputData.getString(KEY_AUDIO_DIRECT_URL)
        val videoChunkSize = inputData.getLong(KEY_HTTP_CHUNK_SIZE, 0L)
        val audioChunkSize = inputData.getLong(KEY_AUDIO_HTTP_CHUNK_SIZE, 0L)
        val isPlaceholder = inputData.getBoolean(KEY_IS_PLACEHOLDER, false)

        val scratchDir = File(appContext.cacheDir, "downloads/$taskId")
        scratchDir.mkdirs()

        try {
            val success = concurrencyGate.withDownloadPermit {
                var effectiveDirectUrl = directUrl
                var effectiveAudioDirectUrl = audioDirectUrl
                var effectiveRequiresMuxing = requiresMuxing
                var effectiveVideoChunkSize = videoChunkSize
                var effectiveAudioChunkSize = audioChunkSize
                var effectiveMuxStrategy = muxStrategy
                var effectiveContainer = container

                // JIT Resolution for placeholder items (e.g. fast playlist items)
                if (isPlaceholder || effectiveDirectUrl.isNullOrBlank()) {
                    Logger.i(tag, "[$taskId] JIT resolving stream formats for: $sourceUrl")
                    eventBus.emit(
                        DownloadEvent.Running(
                            taskId = taskId,
                            progress = 0f,
                            downloadedBytes = 0L,
                            totalBytes = null,
                            phase = DownloadPhase.DOWNLOADING
                        )
                    )

                    val extractor = extractorRegistry.resolve(sourceUrl)
                    if (extractor == null) {
                        val err = "No extractor available for $sourceUrl"
                        Logger.e(tag, "[$taskId] $err")
                        eventBus.emit(DownloadEvent.Failed(taskId, err))
                        return@withDownloadPermit false
                    }

                    when (val result = extractor.extract(sourceUrl)) {
                        is ExtractionResult.Success -> {
                            val resolvedItem = result.items.firstOrNull()
                            if (resolvedItem == null) {
                                val err = "No media item returned for $sourceUrl"
                                Logger.e(tag, "[$taskId] $err")
                                eventBus.emit(DownloadEvent.Failed(taskId, err))
                                return@withDownloadPermit false
                            }

                            val isAudio = formatKind == "AUDIO"
                            val resolvedFormat = if (isAudio) {
                                resolvedItem.audioFormats.firstOrNull() ?: resolvedItem.videoFormats.firstOrNull()
                            } else {
                                resolvedItem.videoFormats.firstOrNull() ?: resolvedItem.audioFormats.firstOrNull()
                            }

                            if (resolvedFormat == null || resolvedFormat.directUrl.isNullOrBlank()) {
                                val err = "No direct stream URL resolved for $sourceUrl"
                                Logger.e(tag, "[$taskId] $err")
                                eventBus.emit(DownloadEvent.Failed(taskId, err))
                                return@withDownloadPermit false
                            }

                            effectiveDirectUrl = resolvedFormat.directUrl
                            effectiveRequiresMuxing = resolvedFormat.requiresMuxing
                            effectiveVideoChunkSize = resolvedFormat.httpChunkSizeBytes ?: 0L
                            effectiveMuxStrategy = resolvedFormat.muxStrategy
                            effectiveContainer = if (!container.isNullOrBlank() && container != "Default") container else resolvedFormat.container

                            if (resolvedFormat.requiresMuxing) {
                                val pairedAudio = resolvedItem.audioFormats.find { it.id == resolvedFormat.pairedAudioFormatId }
                                    ?: resolvedItem.audioFormats.firstOrNull()
                                effectiveAudioDirectUrl = pairedAudio?.directUrl
                                effectiveAudioChunkSize = pairedAudio?.httpChunkSizeBytes ?: 0L
                            }
                        }
                        is ExtractionResult.Error -> {
                            val err = "JIT stream resolution failed: ${result.cause.message}"
                            Logger.e(tag, "[$taskId] $err")
                            eventBus.emit(DownloadEvent.Failed(taskId, err))
                            return@withDownloadPermit false
                        }
                    }
                }

                if (effectiveDirectUrl.isNullOrBlank()) {
                    val err = "Missing direct download URL"
                    Logger.e(tag, "[$taskId] $err")
                    eventBus.emit(DownloadEvent.Failed(taskId, err))
                    return@withDownloadPermit false
                }

                Logger.i(tag, "[$taskId] Acquired download permit, starting: title='$title', container=$effectiveContainer, requiresMuxing=$effectiveRequiresMuxing")
                eventBus.emit(
                    DownloadEvent.Running(
                        taskId = taskId,
                        progress = 0f,
                        downloadedBytes = 0L,
                        totalBytes = null,
                        phase = DownloadPhase.DOWNLOADING
                    )
                )

                val finalFile: File

                if (effectiveRequiresMuxing && !effectiveAudioDirectUrl.isNullOrBlank()) {
                    // Dual-stream download for YouTube video + audio muxing (§9)
                    val videoTemp = File(scratchDir, "video.tmp")
                    val audioTemp = File(scratchDir, "audio.tmp")

                    Logger.d(tag, "[$taskId] Downloading video stream (chunkSize=$effectiveVideoChunkSize)...")
                    chunkedDownloader.download(
                        url = effectiveDirectUrl,
                        destination = videoTemp,
                        chunkSizeBytes = effectiveVideoChunkSize,
                        platform = platform,
                        isStopped = { isStopped }
                    ) { bytesWritten, totalBytes ->
                        val progress = if (totalBytes != null && totalBytes > 0) {
                            (bytesWritten.toFloat() / totalBytes) * 0.8f // Video accounts for 80% of download progress
                        } else {
                            0.2f
                        }
                        eventBus.tryEmit(
                            DownloadEvent.Running(
                                taskId = taskId,
                                progress = progress,
                                downloadedBytes = bytesWritten,
                                totalBytes = totalBytes,
                                phase = DownloadPhase.DOWNLOADING
                            )
                        )
                    }

                    Logger.d(tag, "[$taskId] Downloading audio stream (chunkSize=$effectiveAudioChunkSize)...")
                    chunkedDownloader.download(
                        url = effectiveAudioDirectUrl,
                        destination = audioTemp,
                        chunkSizeBytes = effectiveAudioChunkSize,
                        platform = platform,
                        isStopped = { isStopped }
                    ) { bytesWritten, totalBytes ->
                    val progress = if (totalBytes != null && totalBytes > 0) {
                        0.8f + (bytesWritten.toFloat() / totalBytes) * 0.15f // Audio accounts for 15%
                    } else {
                        0.9f
                    }
                    eventBus.tryEmit(
                        DownloadEvent.Running(
                            taskId = taskId,
                            progress = progress,
                            downloadedBytes = videoTemp.length() + bytesWritten,
                            totalBytes = totalBytes?.let { it + videoTemp.length() },
                            phase = DownloadPhase.DOWNLOADING
                        )
                    )
                }

                // Muxing Phase with FFmpeg (§2.4 / §9)
                Logger.i(tag, "[$taskId] Both streams downloaded. Starting FFmpeg muxing into $container...")
                eventBus.emit(
                    DownloadEvent.Running(
                        taskId = taskId,
                        progress = 0.95f,
                        downloadedBytes = videoTemp.length() + audioTemp.length(),
                        totalBytes = videoTemp.length() + audioTemp.length(),
                        phase = DownloadPhase.MUXING
                    )
                )

                val ext = effectiveContainer.lowercase(Locale.ROOT)
                val muxedFile = File(scratchDir, "muxed.$ext")

                val ffmpegArgs = buildMuxArgs(
                    videoFile = videoTemp,
                    audioFile = audioTemp,
                    outputFile = muxedFile,
                    container = effectiveContainer,
                    muxStrategy = effectiveMuxStrategy
                )

                var muxSuccess = false
                var muxError = ""
                ffmpegProcessExecutor.run(ffmpegArgs).collect { event ->
                    when (event) {
                        is FFmpegEvent.Progress -> {
                            Logger.d(tag, "[$taskId] Mux progress: timeSeconds=${event.timeSeconds}")
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
                    val err = "FFmpeg muxing failed: $muxError"
                    Logger.e(tag, "[$taskId] $err")
                    eventBus.emit(DownloadEvent.Failed(taskId, err))
                    return@withDownloadPermit false
                }

                finalFile = muxedFile
            } else {
                // Single-stream download (Progressive video, non-YouTube, or audio-only)
                val ext = effectiveContainer.lowercase(Locale.ROOT)
                val mediaTemp = File(scratchDir, "media.tmp")

                Logger.d(tag, "[$taskId] Downloading single media stream (chunkSize=$effectiveVideoChunkSize)...")
                chunkedDownloader.download(
                    url = effectiveDirectUrl,
                    destination = mediaTemp,
                    chunkSizeBytes = effectiveVideoChunkSize,
                    platform = platform,
                    isStopped = { isStopped }
                ) { bytesWritten, totalBytes ->
                    val progress = if (totalBytes != null && totalBytes > 0) {
                        bytesWritten.toFloat() / totalBytes
                    } else {
                        0.5f
                    }
                    eventBus.tryEmit(
                        DownloadEvent.Running(
                            taskId = taskId,
                            progress = progress,
                            downloadedBytes = bytesWritten,
                            totalBytes = totalBytes,
                            phase = DownloadPhase.DOWNLOADING
                        )
                    )
                }

                val convertedFile = File(scratchDir, "output.$ext")
                if (mediaTemp.renameTo(convertedFile)) {
                    finalFile = convertedFile
                } else {
                    finalFile = mediaTemp
                }
            }

            // Export to MediaStore / Scoped Storage (§9.7)
            Logger.i(tag, "[$taskId] Exporting ${finalFile.length()} bytes to MediaStore...")
            val isAudioOnly = formatKind.equals("AUDIO", ignoreCase = true) ||
                    effectiveContainer.uppercase(Locale.ROOT) in listOf("MP3", "M4A", "OPUS", "FLAC", "WAV", "AAC", "OGG") ||
                    formatLabel.contains("MP3", ignoreCase = true) ||
                    formatLabel.contains("M4A", ignoreCase = true) ||
                    formatLabel.contains("OPUS", ignoreCase = true) ||
                    formatLabel.contains("FLAC", ignoreCase = true)

            val outputUri = mediaStoreExporter.export(
                file = finalFile,
                title = title,
                author = author,
                container = effectiveContainer,
                isAudio = isAudioOnly
            )

            if (outputUri == null) {
                Logger.e(tag, "[$taskId] Failed to write file to MediaStore")
                eventBus.emit(DownloadEvent.Failed(taskId, "Failed to save file to system media storage"))
                return@withDownloadPermit false
            }

            Logger.i(tag, "[$taskId] Download completed successfully: uri=$outputUri, size=${finalFile.length()}")
            eventBus.emit(
                DownloadEvent.Completed(
                    taskId = taskId,
                    outputUri = outputUri.toString(),
                    fileSizeBytes = finalFile.length()
                )
            )

            // Persist to Room DownloadHistoryEntity (§10)
            val historyEntity = DownloadHistoryEntity(
                id = taskId,
                platform = platform,
                sourceUrl = sourceUrl,
                title = title,
                author = author,
                thumbnailUrl = thumbnailUrl,
                localUri = outputUri.toString(),
                formatLabel = formatLabel,
                container = container,
                fileSizeBytes = finalFile.length(),
                completedAt = System.currentTimeMillis()
            )
            downloadDao.insertHistory(historyEntity)
                true
            }

            if (success) Result.success() else Result.failure()
        } catch (e: CancellationException) {
            Logger.i(tag, "[$taskId] Download task was cancelled")
            throw e
        } catch (e: Exception) {
            if (isStopped) {
                Logger.i(tag, "[$taskId] Download task was stopped")
                Result.failure()
            } else {
                Logger.e(tag, "[$taskId] Download task failed with exception", e)
                eventBus.emit(DownloadEvent.Failed(taskId, e.message ?: "Download failed"))
                Result.failure()
            }
        } finally {
            // Clean up task scratch directory (§9.1)
            scratchDir.deleteRecursively()
        }
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
