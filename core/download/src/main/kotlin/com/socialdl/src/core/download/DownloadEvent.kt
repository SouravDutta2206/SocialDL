package com.socialdl.src.core.download

sealed interface DownloadEvent {
    /**
     * One-shot UI toast signal emitted right before the success transaction commits.
     * Room [DownloadJobRepository.observeJobs] is the lifecycle truth; this bus
     * carries NO lifecycle state and must never be used for status.
     */
    data class Completed(
        val jobId: String,
        val title: String,
        val outputUri: String,
        val fileSizeBytes: Long
    ) : DownloadEvent
}
