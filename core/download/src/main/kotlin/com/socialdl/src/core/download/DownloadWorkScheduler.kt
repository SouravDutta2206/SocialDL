package com.socialdl.src.core.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.await
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager seam for [DownloadManager] (Phase 3 / finding #3).
 *
 * Enqueueing the work request is the non-transactional half of the enqueue
 * critical section: it must stay inside the enqueue mutex so that a caller which
 * coalesces onto another caller's job cannot return an id before the winner's
 * WorkRequest actually exists. Keeping the scheduler behind an interface makes
 * that ordering verifiable in a plain unit test (a fake records the calls and can
 * hold the enqueue open) instead of booting WorkManager.
 *
 * The seam is also what makes `DownloadManager` constructible off-device: it
 * no longer touches `WorkManager.getInstance` directly.
 */
interface DownloadWorkScheduler {

    /**
     * Schedules the single WorkRequest for [jobId] and suspends until WorkManager
     * has actually persisted it. The request carries only the job id — the worker
     * reads its full config from the Room row.
     *
     * [wifiOnly] is evaluated at enqueue time and encoded as a network constraint.
     *
     * Contract: this must NOT return before the request is stored. WorkManager's
     * `enqueue()` is asynchronous — it hands back an `Operation` immediately and
     * persists on its own executor — so returning early would release the caller's
     * enqueue mutex (and let coalesced callers receive an id) before any work
     * exists, and a later persistence failure would bypass the QUEUED-row cleanup.
     *
     * @throws Exception when WorkManager cannot accept the request; the caller
     *   deletes the QUEUED row and rethrows so batch counts stay truthful.
     */
    suspend fun enqueue(jobId: String, wifiOnly: Boolean)

    /**
     * Cancels every work request tagged with [jobId]; no-op when none exists.
     *
     * Best-effort by design: the authoritative cancel is the deleted Room row
     * (the worker's guard read aborts), so this does not await WorkManager.
     */
    suspend fun cancel(jobId: String)
}

/** Production [DownloadWorkScheduler]: one tagged [OneTimeWorkRequestBuilder] per job. */
@Singleton
class WorkManagerDownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : DownloadWorkScheduler {

    /**
     * Submission hook. Production talks to WorkManager; tests substitute a fake
     * [Operation] to exercise the asynchronous completion contract of [enqueue]
     * without booting WorkManager. Production never reassigns it.
     */
    internal var submitRequest: (WorkRequest) -> Operation = { request ->
        WorkManager.getInstance(context).enqueue(request)
    }

    override suspend fun enqueue(jobId: String, wifiOnly: Boolean) {
        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_JOB_ID to jobId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                    )
                    .build()
            )
            .addTag(jobId)
            .build()
        // WorkManager.enqueue() returns an Operation whose future completes only
        // after the WorkSpec is persisted (or fails with the persistence error).
        // Await it so the caller's mutex + row-cleanup cover enqueue completion.
        submitRequest(workRequest).await()
    }

    override suspend fun cancel(jobId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(jobId)
    }
}
