package com.socialdl.src.core.download

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.socialdl.src.core.common.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

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
