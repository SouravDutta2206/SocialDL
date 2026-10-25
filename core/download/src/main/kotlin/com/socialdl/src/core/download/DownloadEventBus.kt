package com.socialdl.src.core.download

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transient in-process signal channel from DownloadWorker to DownloadManager.
 *
 * Contract:
 * - `replay = 0` on purpose: the bus is a fire-and-forget signal, never truth.
 *   [DownloadManager] holds the only authoritative state in `_activeTasks`;
 *   lifecycle entry (Queued) and exit (Cancelled) are written there directly and
 *   are NOT evented. Only worker-observed transitions (Running/Completed/Failed)
 *   flow through the bus.
 * - [emit] (suspend) is for state-critical worker transitions (Completed, Failed,
 *   and phase-progress updates) — the worker must wait for delivery, else the UI
 *   could miss a terminal state.
 * - [tryEmit] is only acceptable for high-frequency progress ticks where dropping
 *   a sample is harmless (the next tick corrects the UI).
 * - The sole subscriber is [DownloadManager]; do not add lifecycle-truth events here.
 */
@Singleton
class DownloadEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    suspend fun emit(event: DownloadEvent) {
        _events.emit(event)
    }

    fun tryEmit(event: DownloadEvent): Boolean {
        return _events.tryEmit(event)
    }
}
