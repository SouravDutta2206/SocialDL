package com.socialdl.src.feature.settings

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.socialdl.src.core.cookies.CAPTURABLE_PLATFORMS
import com.socialdl.src.core.cookies.CookieCaptureController
import com.socialdl.src.core.cookies.CookieStore
import com.socialdl.src.core.model.Platform
import com.socialdl.src.core.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state backed by [SettingsRepository].
 *
 * Every preference is consumed by the download pipeline: concurrency (semaphore
 * size), Wi-Fi-only (WorkManager network constraint) and force-H.264 (mux strategy).
 */
data class SettingsUiState(
    val maxConcurrentDownloads: Int = 3,
    val wifiOnlyDownloads: Boolean = false,
    val forceH264Transcode: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val cookieStore: CookieStore,
    private val cookieCaptureController: CookieCaptureController,
) : ViewModel() {

    private val tag = "SettingsViewModel"

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.maxConcurrentDownloads,
        settingsRepository.wifiOnlyDownloads,
        settingsRepository.forceH264Transcode
    ) { max, wifiOnly, forceH264 ->
        SettingsUiState(
            maxConcurrentDownloads = max,
            wifiOnlyDownloads = wifiOnly,
            forceH264Transcode = forceH264
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState()
    )

    fun setMaxConcurrentDownloads(max: Int) {
        viewModelScope.launch {
            settingsRepository.setMaxConcurrentDownloads(max)
        }
    }

    fun setWifiOnlyDownloads(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWifiOnlyDownloads(enabled)
        }
    }

    fun setForceH264Transcode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setForceH264Transcode(enabled)
        }
    }

    // ---- Cookies (§2.6) ----

    /** One platform's cookie-panel state. */
    data class CookiesUiState(
        val present: Boolean = false,
        val lastRefreshedAt: Long? = null,
        /** Copy for an import/clear outcome; self-clears after a few seconds. */
        val actionMessage: String? = null,
    )

    private val cookieActionMessages = MutableStateFlow<Map<Platform, String?>>(emptyMap())

    /** Per-platform auto-clear timers, cancelled when a newer message supersedes them. */
    private val cookieMessageJobs = mutableMapOf<Platform, Job>()

    /** Bumps whenever the panels must re-stat the jars (capture returned, import, clear). */
    private val cookieTick = MutableStateFlow(0)

    /**
     * Per-platform cookie state for the Settings panels, re-read on [refreshCookieStates].
     */
    val cookieStates: StateFlow<Map<Platform, CookiesUiState>> = combine(
        cookieTick,
        cookieActionMessages
    ) { _, messages ->
        @Suppress("NAME_SHADOWING")
        withContext(Dispatchers.IO) {
            CAPTURABLE_PLATFORMS.associateWith { platform ->
                val jar = cookieStore.jarState(platform)
                CookiesUiState(
                    present = jar != null,
                    lastRefreshedAt = jar?.lastRefreshedAt,
                    actionMessage = messages[platform],
                )
            }
        }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    /** Re-stats the jars; call when the Settings screen becomes visible again. */
    fun refreshCookieStates() {
        cookieTick.update { it + 1 }
    }

    /** Manual `cookies.txt` import fallback (§2.6) — validated before it is accepted. */
    fun importCookies(platform: Platform, uri: Uri) {
        viewModelScope.launch {
            try {
                // null = stored; otherwise the store returns the finished, user-facing
                // failure message.
                val reason = cookieStore.importNetscapeFile(platform, uri)
                setCookieMessage(
                    platform,
                    reason ?: "Cookies imported — you're signed in for ${platform.displayName} extraction."
                )
            } catch (e: CancellationException) {
                // ViewModel cleared mid-import: not a user-facing failure.
                throw e
            } catch (e: Exception) {
                // IO/crypto failures must surface as panel copy, never crash the app.
                Log.e(tag, "Failed to import cookies for $platform", e)
                setCookieMessage(
                    platform,
                    "Couldn't import cookies: ${e.message ?: "unknown error"}"
                )
            }
            // Always re-stat (even for a repeated message string): the jar may have
            // been replaced, so the presence/age row must follow the store.
            refreshCookieStates()
        }
    }

    /** Destructive — UI confirms before calling. Also resets the capture browser session. */
    fun clearCookies(platform: Platform) {
        viewModelScope.launch {
            try {
                cookieStore.clear(platform)
                cookieCaptureController.clearCaptureSession()
                setCookieMessage(platform, "${platform.displayName} cookies cleared.")
            } catch (e: CancellationException) {
                // ViewModel cleared mid-clear: not a user-facing failure.
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Failed to clear cookies for $platform", e)
                setCookieMessage(
                    platform,
                    "Couldn't clear cookies: ${e.message ?: "unknown error"}"
                )
            }
            // Always re-stat so the panel reflects the actual jar state after a clear
            // attempt, including when an error left the jar in place.
            refreshCookieStates()
        }
    }

    /**
     * Sets one platform's message and clears it after a display window. Each platform
     * owns at most one timer: a newer message cancels the older timer so it cannot
     * clear the new copy early.
     */
    private fun setCookieMessage(platform: Platform, message: String) {
        cookieMessageJobs[platform]?.cancel()
        cookieActionMessages.update { it + (platform to message) }
        cookieMessageJobs[platform] = viewModelScope.launch {
            delay(4_000)
            cookieActionMessages.update { messages ->
                if (messages[platform] == message) messages + (platform to null) else messages
            }
        }
    }
}
