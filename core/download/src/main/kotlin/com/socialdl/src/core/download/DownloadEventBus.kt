package com.socialdl.src.core.download

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot UI toast signal channel from [DownloadWorker] to feature ViewModels.
 *
 * Contract:
 * - `replay = 0` on purpose: the bus is a fire-and-forget signal, never truth.
 *   Room (via [DownloadJobRepository]) is the SINGLE source of truth for the
 *   download lifecycle — no status/progress/queue events may flow through here.
 * - The only event type is [DownloadEvent.Completed], emitted right before the
 *   success transaction commits so the UI can toast "Download completed".
 * - Subscribers must treat events as best-effort: if no ViewModel is subscribed
 *   (app backgrounded), the toast is simply dropped — the durable row/history
 *   state is unaffected.
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
