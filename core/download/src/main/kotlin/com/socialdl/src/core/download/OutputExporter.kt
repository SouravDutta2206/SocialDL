package com.socialdl.src.core.download

import android.content.Context
import android.net.Uri
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.common.util.MediaStorageUtils
import com.socialdl.src.core.database.entity.DownloadJobEntity
import com.socialdl.src.core.model.DownloadStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Exports a finished file to MediaStore / scoped storage and persists the Room
 * history row. Guard reads against instant cancellation run before export, right
 * after the pending row is created, and again before publish; a cancel that wins
 * after publish compensates by deleting the file.
 */
class OutputExporter @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val jobRepository: DownloadJobRepository,
    private val mediaStoreExporter: MediaStoreExporter,
    private val notificationManager: DownloadNotificationManager,
    private val eventBus: DownloadEventBus,
) {
    private val tag = "OutputExporter"

    /**
     * Exports [finalFile] to MediaStore / scoped storage (§9.7) and persists the
     * Room history row (§10). [resolved] carries the container actually downloaded
     * (JIT-resolved placeholders may differ from the stale
     * [DownloadJobEntity.container]).
     * [onExporting] runs once the EXPORTING transition was persisted, so the
     * worker can refresh its foreground notification for the phase change.
     * Returns true on success; false (FAILED written) on export failure.
     */
    internal suspend fun exportAndPersist(
        job: DownloadJobEntity,
        finalFile: File,
        resolved: ResolvedStreams,
        onExporting: (suspend () -> Unit)? = null,
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
        // Bug #7: the MUXING/DOWNLOADING notification must switch to EXPORTING.
        onExporting?.invoke()

        val resolvedJob = job.copy(
            container = resolved.container,
            formatId = resolved.formatId,
            formatKind = resolved.formatKind.name,
            formatLabel = resolved.formatLabel
        )

        val outputUri = mediaStoreExporter.export(
            file = finalFile,
            title = resolvedJob.title,
            container = resolved.container,
            kind = resolved.formatKind,
            // Carousel siblings share this title; the slot keeps their export rows
            // (and therefore their files) distinct instead of overwriting one another.
            carouselIndex = resolvedJob.carouselIndex.takeIf { it > 0 },
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
            jobRepository.markFailed(jobId, DownloadError.ExportFailed)
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
        } catch (e: Exception) {
            // A non-cancellation DB failure after publish would otherwise leak a
            // visible MediaStore row with no history record. Compensate exactly
            // like the cancellation path, then let doWork map the typed error.
            if (!historyRecorded) {
                Logger.e(tag, "[$jobId] Export failed after publish; deleting the published file", e)
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
}
