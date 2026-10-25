package com.socialdl.src.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.socialdl.src.core.settings.SettingsRepository
import com.socialdl.src.core.settings.model.AppTheme
import com.socialdl.src.core.settings.model.SearchEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state backed by [SettingsRepository].
 *
 * NOTE: this is storage-only wiring — the download pipeline consumption of these
 * preferences (concurrency semaphore, Wi-Fi gate, folder redirects) remains deferred.
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
    private val settingsRepository: SettingsRepository
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
}
