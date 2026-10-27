package com.socialdl.src.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.socialdl.src.core.cookies.CookieStore
import com.socialdl.src.core.model.Platform
import com.socialdl.src.core.settings.SettingsRepository
import com.socialdl.src.core.settings.model.AppTheme
import com.socialdl.src.core.settings.model.SearchEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state backed by [SettingsRepository].
 *
 * Download-affecting preferences are now consumed by the pipeline: concurrency
 * (semaphore size), Wi-Fi-only (WorkManager network constraint) and force-H.264
 * (mux strategy). Theme, accent, preferred search engine and SAF folder redirects
 * remain storage-only and are not yet applied by the UI/exporter.
 */
data class SettingsUiState(
    val theme: AppTheme = AppTheme.DARK,
    val searchEngine: SearchEngine = SearchEngine.YOUTUBE,
    val videoFolderUri: Uri? = null,
    val audioFolderUri: Uri? = null,
    val maxConcurrentDownloads: Int = 3,
    val wifiOnlyDownloads: Boolean = false,
    val forceH264Transcode: Boolean = false,
)

private data class DownloadPrefs(
    val maxConcurrentDownloads: Int = 3,
    val wifiOnlyDownloads: Boolean = false,
    val forceH264Transcode: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val cookieStore: CookieStore,
) : ViewModel() {

    private val downloadPrefs = combine(
        settingsRepository.maxConcurrentDownloads,
        settingsRepository.wifiOnlyDownloads,
        settingsRepository.forceH264Transcode
    ) { max, wifiOnly, forceH264 ->
        DownloadPrefs(max, wifiOnly, forceH264)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.theme,
        settingsRepository.fallbackSearchEngine,
        settingsRepository.videoFolderUri,
        settingsRepository.audioFolderUri,
        downloadPrefs
    ) { theme, engine, videoFolder, audioFolder, prefs ->
        SettingsUiState(
            theme = theme,
            searchEngine = engine,
            videoFolderUri = videoFolder,
            audioFolderUri = audioFolder,
            maxConcurrentDownloads = prefs.maxConcurrentDownloads,
            wifiOnlyDownloads = prefs.wifiOnlyDownloads,
            forceH264Transcode = prefs.forceH264Transcode
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState()
    )

    fun onCycleTheme() {
        viewModelScope.launch {
            val current = settingsRepository.theme.first()
            val next = when (current) {
                AppTheme.SYSTEM -> AppTheme.LIGHT
                AppTheme.LIGHT -> AppTheme.DARK
                AppTheme.DARK -> AppTheme.SYSTEM
            }
            settingsRepository.setTheme(next)
        }
    }

    fun onCycleSearchEngine() {
        viewModelScope.launch {
            val current = settingsRepository.fallbackSearchEngine.first()
            val next = when (current) {
                SearchEngine.YOUTUBE -> SearchEngine.PIPED
                SearchEngine.PIPED -> SearchEngine.INVIDIOUS
                SearchEngine.INVIDIOUS -> SearchEngine.YOUTUBE
            }
            settingsRepository.setFallbackSearchEngine(next)
        }
    }

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

    fun setVideoFolder(uri: Uri?) {
        viewModelScope.launch {
            settingsRepository.setVideoFolderUri(uri)
        }
    }

    fun setAudioFolder(uri: Uri?) {
        viewModelScope.launch {
            settingsRepository.setAudioFolderUri(uri)
        }
    }

    // ---- Cookies (§2.6) ----

    data class CookiesUiState(
        val present: Boolean = false,
        val lastRefreshedAt: Long? = null,
        /** Copy for import/clear outcomes; self-clears after a few seconds. */
        val actionMessage: String? = null,
    )

    private val cookiesActionMessage = MutableStateFlow<String?>(null)

    val cookiesUiState: StateFlow<CookiesUiState> = combine(
        cookieStore.lastRefreshedAt(Platform.YOUTUBE),
        cookiesActionMessage
    ) { lastRefreshed, actionMessage ->
        // hasCookies() re-evaluated on every emission, so a capture round-trip from
        // the WebView route (which bumps LAST_REFRESHED in save()) is reflected
        // without an explicit refresh call.
        CookiesUiState(
            present = cookieStore.hasCookies(Platform.YOUTUBE),
            lastRefreshedAt = lastRefreshed,
            actionMessage = actionMessage
        )
    }
        // hasCookies() stats the encrypted jar file — never on the main thread.
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CookiesUiState()
        )

    /** Sets [target]'s message and clears it after a display window. */
    private suspend fun setActionMessage(
        target: MutableStateFlow<String?>,
        message: String,
    ) {
        target.value = message
        delay(4_000)
        if (target.value == message) target.value = null
    }

    /** Shared copy for the manual `cookies.txt` import outcome. */
    private fun importMessage(
        outcome: CookieStore.ImportOutcome,
        platformName: String,
    ): String = when (outcome) {
        is CookieStore.ImportOutcome.Success ->
            "Cookies imported — you're signed in for $platformName extraction."
        is CookieStore.ImportOutcome.Rejected ->
            "Import rejected: ${outcome.reason}"
        CookieStore.ImportOutcome.Unreadable ->
            "Import failed: the selected file could not be read."
    }

    /** Manual `cookies.txt` import fallback (§2.6) — validates before accepting. */
    fun importCookies(uri: Uri) {
        viewModelScope.launch {
            val outcome = cookieStore.importNetscapeFile(Platform.YOUTUBE, uri)
            setActionMessage(cookiesActionMessage, importMessage(outcome, "YouTube"))
        }
    }

    /** Destructive — UI confirms before calling. */
    fun clearCookies() {
        viewModelScope.launch {
            cookieStore.clear(Platform.YOUTUBE)
            setActionMessage(cookiesActionMessage, "YouTube cookies cleared.")
        }
    }

    // ---- Instagram cookies (Phase 5, gallery-dl) ----

    data class InstagramCookiesUiState(
        val present: Boolean = false,
        val lastRefreshedAt: Long? = null,
        /** Copy for import/clear outcomes; self-clears after a few seconds. */
        val actionMessage: String? = null,
    )

    private val instagramActionMessage = MutableStateFlow<String?>(null)

    val instagramCookiesUiState: StateFlow<InstagramCookiesUiState> = combine(
        cookieStore.lastRefreshedAt(Platform.INSTAGRAM),
        instagramActionMessage
    ) { lastRefreshed, actionMessage ->
        InstagramCookiesUiState(
            present = cookieStore.hasCookies(Platform.INSTAGRAM),
            lastRefreshedAt = lastRefreshed,
            actionMessage = actionMessage,
        )
    }
        // hasCookies() stats the encrypted blob — never on the main thread.
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = InstagramCookiesUiState()
        )

    /** Destructive — UI confirms before calling. */
    fun clearInstagramCookies() {
        viewModelScope.launch {
            cookieStore.clear(Platform.INSTAGRAM)
            setActionMessage(instagramActionMessage, "Instagram cookies cleared.")
        }
    }

    /** Manual cookies.txt fallback for gallery-dl (§2.6 escape hatch). */
    fun importInstagramCookies(uri: Uri) {
        viewModelScope.launch {
            val outcome = cookieStore.importNetscapeFile(Platform.INSTAGRAM, uri)
            setActionMessage(instagramActionMessage, importMessage(outcome, "Instagram"))
        }
    }
}
