package com.socialdl.src.core.download

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

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
