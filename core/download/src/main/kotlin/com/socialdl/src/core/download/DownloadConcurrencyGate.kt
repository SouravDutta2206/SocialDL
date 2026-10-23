package com.socialdl.src.core.download

import com.socialdl.src.core.settings.SettingsRepository
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global concurrency limiter for active media downloads.
 * Throttles simultaneously running downloads across single-item and batch requests,
 * keeping remaining tasks safely in QUEUED state without thread starvation or CDN rate limits.
 */
@Singleton
class DownloadConcurrencyGate @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    val semaphore = Semaphore(permits = 3)

    /**
     * Executes the given [action] only after acquiring a download permit.
     * Suspends without blocking worker threads if all permits are currently active.
     */
    suspend fun <T> withDownloadPermit(action: suspend () -> T): T {
        return semaphore.withPermit {
            action()
        }
    }
}
