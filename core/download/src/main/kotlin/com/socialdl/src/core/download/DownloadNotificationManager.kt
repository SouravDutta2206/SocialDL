package com.socialdl.src.core.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.common.util.toHumanReadableSize
import com.socialdl.src.core.model.DownloadStatus
import com.socialdl.src.core.model.FormatKind
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the download foreground-service notification (P1-1):
 * channel creation, notification building with the in-notification
 * Cancel action (instant-deletion semantics via [DownloadCancelReceiver]),
 * and the [ForegroundInfo] the worker promotes itself with.
 */
@Singleton
class DownloadNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val systemNotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            systemNotificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Download progress",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            systemNotificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_COMPLETE_ID,
                    "Download completed",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    /**
     * Posts a completion notification. The in-app toast bus is best-effort and
     * dropped when the app is backgrounded, so the user must be able to learn
     * about a finished download without opening the app.
     */
    fun notifyCompleted(jobId: String, title: String) {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETE_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText("Download complete")
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching {
            systemNotificationManager.notify(jobIdNotificationId(jobId) + COMPLETION_ID_OFFSET, notification)
        }.onFailure { Logger.w("DownloadNotification", "Failed to post completion notification", it) }
    }

    /** Opens the app's launcher activity; null when no launcher intent exists. */
    private fun openAppPendingIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Builds the [ForegroundInfo] for a running job. Promoting uses
     * dataSync on Android 10+ (manifest declares the matching FGS type).
     */
    fun buildForegroundInfo(
        jobId: String,
        title: String,
        status: DownloadStatus,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long?,
        /** Distinguishes a video+audio mux from a single-stream conversion in the text. */
        formatKind: FormatKind? = null
    ): ForegroundInfo {
        ensureChannel()
        val notification =
            buildNotification(jobId, title, status, progress, downloadedBytes, totalBytes, formatKind)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                jobIdNotificationId(jobId),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(jobIdNotificationId(jobId), notification)
        }
    }

    private fun buildNotification(
        jobId: String,
        title: String,
        status: DownloadStatus,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long?,
        formatKind: FormatKind?
    ): Notification {
        val percent = (progress.coerceIn(0f, 1f) * 100).toInt()
        val knownTotal = totalBytes != null && totalBytes > 0L
        val statusText = when (status) {
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> when {
                knownTotal ->
                    "${downloadedBytes.toHumanReadableSize()} of " +
                        "${totalBytes!!.toHumanReadableSize()} ($percent%)"
                downloadedBytes > 0L -> "${downloadedBytes.toHumanReadableSize()} downloaded"
                else -> "Downloading..."
            }
            DownloadStatus.MUXING ->
                if (formatKind == FormatKind.AUDIO) "Converting audio..." else "Merging video and audio..."
            DownloadStatus.EXPORTING -> "Saving to media storage..."
            else -> status.name.lowercase()
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(statusText)
            .setContentIntent(openAppPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(
                0,
                "Cancel",
                DownloadCancelReceiver.pendingIntent(context, jobId)
            )

        when (status) {
            DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED ->
                if (knownTotal) {
                    builder.setProgress(100, percent, percent == 0)
                } else {
                    // No usable total: the percent would be a lie, so show an
                    // indeterminate bar and let the text carry the byte count.
                    builder.setProgress(0, 0, true)
                }
            DownloadStatus.MUXING, DownloadStatus.EXPORTING ->
                builder.setProgress(0, 0, true)
            else -> Unit
        }

        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "download_progress"
        const val CHANNEL_COMPLETE_ID = "download_complete"
        private const val COMPLETION_ID_OFFSET = 1
    }
}

/**
 * Stable notification / PendingIntent id derived from the job's UUID bits.
 * Replaces raw `jobId.hashCode()`, which could collide across two concurrent
 * jobs — one notification would overwrite the other's progress and cancel
 * action. UUID bits xor'd then truncated to Int distributes far more evenly.
 */
fun jobIdNotificationId(jobId: String): Int =
    runCatching {
        val uuid = UUID.fromString(jobId)
        (uuid.mostSignificantBits xor uuid.leastSignificantBits).toInt()
    }.getOrDefault(jobId.hashCode())

/**
 * The in-notification Cancel action (P1-1): runs the SAME instant-deletion
 * semantics as the UI X — delete the Room row (card vanishes), then cancel the
 * WorkManager job; the running worker's guard read aborts the pipeline.
 */
@AndroidEntryPoint
class DownloadCancelReceiver : BroadcastReceiver() {

    @Inject
    lateinit var downloadManager: DownloadManager

    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return
        val result = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // runCatching: an unhandled exception here (Room not ready,
                // WorkManager init failure) would crash the process during a
                // user-initiated cancel from the notification shade.
                runCatching { downloadManager.cancelDownload(jobId) }
                    .onFailure { Logger.e("DownloadCancelReceiver", "Cancel action failed for job $jobId", it) }
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val EXTRA_JOB_ID = "job_id"

        fun pendingIntent(context: Context, jobId: String): PendingIntent {
            val intent = Intent(context, DownloadCancelReceiver::class.java).apply {
                putExtra(EXTRA_JOB_ID, jobId)
            }
            return PendingIntent.getBroadcast(
                context,
                jobIdNotificationId(jobId),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
