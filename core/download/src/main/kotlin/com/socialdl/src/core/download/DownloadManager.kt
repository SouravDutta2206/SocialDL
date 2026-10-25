package com.socialdl.src.core.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.database.dao.DownloadDao
import com.socialdl.src.core.model.DownloadStatus
import com.socialdl.src.core.model.DownloadTask
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: DownloadEventBus,
    private val downloadDao: DownloadDao,
) {
    private val tag = "DownloadManager"
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Logger.e(tag, "Uncaught error in DownloadManager event collection", e)
        }
    )

    // Single source of truth for visible download tasks. Direct writes (enqueueDownload/cancelDownload)
    // own lifecycle entry/exit; worker events (Running/Completed/Failed) are the other writer.
    // The event bus itself carries no lifecycle truth — see DownloadEventBus KDoc.
    private val _activeTasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val activeTasks: StateFlow<Map<String, DownloadTask>> = _activeTasks.asStateFlow()

    init {
        scope.launch {
            eventBus.events.collect { event ->
                handleEvent(event)
            }
        }
    }

    private fun handleEvent(event: DownloadEvent) {
        when (event) {
            // No-op: enqueueDownload registers the task directly in _activeTasks (see below),
            // so the collector must not double-write it. Queued events are no longer emitted.
            is DownloadEvent.Queued -> Unit
            is DownloadEvent.Running -> {
                _activeTasks.update { current ->
                    val existing = current[event.taskId] ?: return@update current
                    val newStatus = when (event.phase) {
                        DownloadPhase.DOWNLOADING -> DownloadStatus.DOWNLOADING
                        DownloadPhase.MUXING -> DownloadStatus.MUXING
                    }
                    val updated = existing.copy(
                        status = newStatus,
                        progress = event.progress,
                        downloadedBytes = event.downloadedBytes,
                        totalBytes = event.totalBytes ?: existing.totalBytes
                    )
                    current + (event.taskId to updated)
                }
            }
            is DownloadEvent.Completed -> {
                _activeTasks.update { current ->
                    current - event.taskId
                }
            }
            is DownloadEvent.Failed -> {
                // Failed tasks intentionally stay in the map with status FAILED so the UI shows the failure.
                // They are dismissible via cancelDownload(taskId), which removes them from the map.
                _activeTasks.update { current ->
                    val existing = current[event.taskId] ?: return@update current
                    val failedTask = existing.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = event.error
                    )
                    current + (event.taskId to failedTask)
                }
            }
            // No-op: cancelDownload removes the entry directly from _activeTasks.
            // The worker never emits Cancelled (cancellation kills it before it could).
            is DownloadEvent.Cancelled -> Unit
        }
    }

    fun enqueueDownload(
        mediaItem: MediaItem,
        selectedFormat: MediaFormat,
        container: String? = null,
        customTitle: String? = null,
        customAuthor: String? = null,
        groupId: String? = null
    ): String {
        val taskId = UUID.randomUUID().toString()
        val finalContainer = if (!container.isNullOrBlank() && container != "Default") {
            container
        } else {
            selectedFormat.container
        }

        val title = customTitle ?: mediaItem.title
        val author = customAuthor ?: mediaItem.author

        // If format requires muxing, resolve paired audio stream
        val pairedAudio = if (selectedFormat.requiresMuxing) {
            mediaItem.audioFormats.find { it.id == selectedFormat.pairedAudioFormatId }
                ?: mediaItem.audioFormats.firstOrNull()
        } else {
            null
        }

        Logger.i(tag, "Enqueuing task $taskId for '${title}', format: ${selectedFormat.label}, container: $finalContainer, pairedAudio=${pairedAudio?.id}, groupId=$groupId")

        val inputData = workDataOf(
            DownloadWorker.KEY_TASK_ID to taskId,
            DownloadWorker.KEY_SOURCE_URL to mediaItem.sourceUrl,
            DownloadWorker.KEY_TITLE to title,
            DownloadWorker.KEY_AUTHOR to author,
            DownloadWorker.KEY_THUMBNAIL_URL to mediaItem.thumbnailUrl,
            DownloadWorker.KEY_PLATFORM to mediaItem.platform.name,
            DownloadWorker.KEY_CONTAINER to finalContainer,
            DownloadWorker.KEY_FORMAT_LABEL to selectedFormat.label,
            DownloadWorker.KEY_DIRECT_URL to (selectedFormat.directUrl ?: ""),
            DownloadWorker.KEY_REQUIRES_MUXING to selectedFormat.requiresMuxing,
            DownloadWorker.KEY_AUDIO_DIRECT_URL to (pairedAudio?.directUrl ?: ""),
            DownloadWorker.KEY_AUDIO_CONTAINER to (pairedAudio?.container ?: "M4A"),
            DownloadWorker.KEY_MUX_STRATEGY to (selectedFormat.muxStrategy?.name ?: ""),
            DownloadWorker.KEY_HTTP_CHUNK_SIZE to (selectedFormat.httpChunkSizeBytes ?: 0L),
            DownloadWorker.KEY_AUDIO_HTTP_CHUNK_SIZE to (pairedAudio?.httpChunkSizeBytes ?: 0L),
            DownloadWorker.KEY_FORMAT_KIND to selectedFormat.kind.name,
            DownloadWorker.KEY_GROUP_ID to (groupId ?: ""),
            DownloadWorker.KEY_IS_PLACEHOLDER to selectedFormat.isPlaceholder
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .addTag(taskId)
            .build()

        val task = DownloadTask(
            id = taskId,
            mediaItem = mediaItem,
            selectedFormat = selectedFormat,
            status = DownloadStatus.QUEUED,
            progress = 0f,
            totalBytes = selectedFormat.approxSizeBytes
        )

        // Register the task synchronously (before WorkManager.enqueue) so the UI never misses
        // the Queued state even if a Running/Failed/Cancelled update arrives out of order.
        // This direct write is the ONLY Queued registration — no Queued event is emitted;
        // _activeTasks is the single source of truth for lifecycle entry/exit (see DownloadEventBus KDoc).
        _activeTasks.update { it + (taskId to task) }

        WorkManager.getInstance(context).enqueue(workRequest)

        return taskId
    }

    fun cancelDownload(taskId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(taskId)
        // Direct removal — no Cancelled event is emitted; the collector no longer mirrors it.
        _activeTasks.update { it - taskId }
    }
}
