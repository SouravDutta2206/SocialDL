package com.socialdl.src.core.common.util

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A one-shot user message: toast/snackbar copy with a severity hint. `isError`
 * lets screens pick the right toast style (error pill vs success pill) without
 * string sniffing.
 */
data class UiMessage(
    val message: String,
    val isError: Boolean = false,
)

/**
 * Shared one-shot user-message channel for ViewModels (toasts/snackbars).
 *
 * `replay = 0`: events fire once for active collectors only — nothing re-shows
 * after a process restore or screen rotation. `extraBufferCapacity` absorbs
 * bursts (e.g. a batch of completions); on overflow the oldest event is dropped —
 * a missed toast is preferred over a stuck one. Toast ids still come from
 * System.currentTimeMillis() in the UI layer.
 *
 * Replaces the three divergent hand-rolled implementations (Home `infoMessage`
 * in UiState, Downloads `_userMessage` StateFlow, Settings inline action copy).
 */
class UiMessageBus {
    private val _events = MutableSharedFlow<UiMessage>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<UiMessage> = _events.asSharedFlow()

    fun post(message: String, isError: Boolean = false) {
        _events.tryEmit(UiMessage(message, isError))
    }
}
