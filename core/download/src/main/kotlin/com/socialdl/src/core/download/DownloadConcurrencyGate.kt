package com.socialdl.src.core.download

import com.socialdl.src.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global concurrency limiter for active media downloads.
 *
 * The permit count follows the user's "Max concurrent downloads" setting. A
 * change swaps in a new semaphore; workers already holding a permit finish on
 * the old one, while newly started workers honor the new limit. That is
 * intentionally simple and avoids resizing a semaphore while it has waiters.
 */
@Singleton
class DownloadConcurrencyGate @Inject constructor(
    settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val semaphore: StateFlow<Semaphore> = settingsRepository.maxConcurrentDownloads
        .map { max -> Semaphore(permits = max.coerceIn(MIN_PERMITS, MAX_PERMITS)) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = Semaphore(permits = DEFAULT_PERMITS)
        )

    /**
     * Executes the given [action] only after acquiring a download permit.
     * Suspends without blocking worker threads if all permits are currently active.
     */
    suspend fun <T> withDownloadPermit(action: suspend () -> T): T {
        return semaphore.value.withPermit { action() }
    }

    private companion object {
        const val DEFAULT_PERMITS = 3
        const val MIN_PERMITS = 1
        const val MAX_PERMITS = 10
    }
}
