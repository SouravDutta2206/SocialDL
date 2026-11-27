package com.socialdl.src.feature.settings

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.socialdl.src.core.common.util.UiMessage
import com.socialdl.src.core.common.util.UiMessageBus
import com.socialdl.src.core.cookies.CAPTURABLE_PLATFORMS
import com.socialdl.src.core.cookies.CookieCaptureController
import com.socialdl.src.core.cookies.CookieStore
import com.socialdl.src.core.cookies.cookieCaptureConfig
import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.Platform
import com.socialdl.src.core.settings.AppSettings
import com.socialdl.src.core.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Saved cookie entry rendered on the Cookies settings subpage. */
data class CookieEntryUiState(
    val platform: Platform,
    val originUrl: String,
    val lastRefreshedAt: Long?,
    val enabled: Boolean,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val cookieStore: CookieStore,
    private val cookieCaptureController: CookieCaptureController,
) : ViewModel() {

    private val tag = "SettingsViewModel"
    private val messageBus = UiMessageBus()

    val messages: SharedFlow<UiMessage> = messageBus.events

    val appSettings: StateFlow<AppSettings> = settingsRepository.appSettings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AppSettings()
        )

    /** Bumps whenever saved jars must be re-stat'd (capture returned, import, clear). */
    private val cookieTick = MutableStateFlow(0)

    val savedCookieEntries: StateFlow<List<CookieEntryUiState>> = combine(
        cookieTick,
        settingsRepository.appSettings
    ) { _, settings ->
        withContext(Dispatchers.IO) {
            CAPTURABLE_PLATFORMS.mapNotNull { platform ->
                val jar = cookieStore.jarState(platform) ?: return@mapNotNull null
                CookieEntryUiState(
                    platform = platform,
                    originUrl = cookieCaptureConfig(platform).successOrigin,
                    lastRefreshedAt = jar.lastRefreshedAt,
                    enabled = settings.isPlatformCookieEnabled(platform),
                )
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // ---- Folders ----

    fun setCheckAvailableStorage(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCheckAvailableStorage(enabled)
        }
    }

    fun setTrimFilenames(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setTrimFilenames(enabled)
        }
    }

    fun setFolderUri(kind: FormatKind, uri: String?) {
        viewModelScope.launch {
            val label = when (kind) {
                FormatKind.AUDIO -> {
                    settingsRepository.setAudioFolderUri(uri)
                    "Audio"
                }
                FormatKind.VIDEO -> {
                    settingsRepository.setVideoFolderUri(uri)
                    "Video"
                }
                FormatKind.IMAGE -> {
                    settingsRepository.setImageFolderUri(uri)
                    "Image"
                }
            }
            messageBus.post("$label folder updated")
        }
    }

    // ---- Downloads ----

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

    fun setMaxConcurrentFragments(max: Int) {
        viewModelScope.launch {
            settingsRepository.setMaxConcurrentFragments(max)
        }
    }

    fun setMaxConcurrentDownloads(max: Int) {
        viewModelScope.launch {
            settingsRepository.setMaxConcurrentDownloads(max)
        }
    }

    // ---- Cookies ----

    fun setUseCookies(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setUseCookies(enabled)
        }
    }

    fun setPlatformCookieEnabled(platform: Platform, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPlatformCookieEnabled(platform, enabled)
        }
    }

    /** Exports the stored jar as a `cookies.txt` into the user-picked SAF document. */
    fun exportCookies(platform: Platform, uri: Uri) {
        viewModelScope.launch {
            try {
                val reason = cookieStore.exportNetscapeFile(platform, uri)
                if (reason == null) {
                    messageBus.post("${platform.displayName} cookies exported")
                } else {
                    messageBus.post(reason, isError = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Failed to export cookies for $platform", e)
                messageBus.post(
                    "Couldn't export cookies: ${e.message ?: "unknown error"}",
                    isError = true
                )
            }
        }
    }

    /** Re-stats saved cookie jars; call when the Cookies screen resumes. */
    fun refreshCookieStates() {
        cookieTick.update { it + 1 }
    }

    /** Manual `cookies.txt` import fallback — validated before it is accepted. */
    fun importCookies(platform: Platform, uri: Uri) {
        viewModelScope.launch {
            try {
                val reason = cookieStore.importNetscapeFile(platform, uri)
                if (reason == null) {
                    messageBus.post("${platform.displayName} cookies imported")
                } else {
                    messageBus.post(reason, isError = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Failed to import cookies for $platform", e)
                messageBus.post(
                    "Couldn't import cookies: ${e.message ?: "unknown error"}",
                    isError = true
                )
            }
            refreshCookieStates()
        }
    }

    /** Destructive — UI confirms before calling. Also resets the capture browser session. */
    fun clearCookies(platform: Platform) {
        viewModelScope.launch {
            try {
                cookieStore.clear(platform)
                cookieCaptureController.clearCaptureSession()
                messageBus.post("${platform.displayName} cookies deleted")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Failed to clear cookies for $platform", e)
                messageBus.post(
                    "Couldn't delete cookies: ${e.message ?: "unknown error"}",
                    isError = true
                )
            }
            refreshCookieStates()
        }
    }

    fun showMessage(message: String, isError: Boolean = false) {
        messageBus.post(message, isError)
    }
}
