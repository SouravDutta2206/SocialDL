package com.socialdl.src.core.settings

import com.socialdl.src.core.model.Platform
import kotlinx.coroutines.flow.Flow

/**
 * Settings repository contract per §2.7.
 * Backed by Jetpack DataStore preferences.
 */
interface SettingsRepository {
    val appSettings: Flow<AppSettings>

    val maxConcurrentDownloads: Flow<Int>
    val wifiOnlyDownloads: Flow<Boolean>
    val forceH264Transcode: Flow<Boolean>

    /**
     * True once the app has shown its first-launch permission prompt
     * (`POST_NOTIFICATIONS` on API 33+, `WRITE_EXTERNAL_STORAGE` on API <= 28).
     * Persisted so the prompt runs exactly once per install, regardless of the
     * user's answer.
     */
    val startupPermissionsRequested: Flow<Boolean>

    suspend fun setMaxConcurrentDownloads(max: Int)
    suspend fun setWifiOnlyDownloads(wifiOnly: Boolean)
    suspend fun setForceH264Transcode(force: Boolean)
    suspend fun setStartupPermissionsRequested(requested: Boolean)

    suspend fun setAudioFolderUri(uri: String?)
    suspend fun setVideoFolderUri(uri: String?)
    suspend fun setImageFolderUri(uri: String?)
    suspend fun setCheckAvailableStorage(enabled: Boolean)
    suspend fun setTrimFilenames(enabled: Boolean)
    suspend fun setMaxConcurrentFragments(max: Int)
    suspend fun setUseCookies(enabled: Boolean)
    suspend fun setPlatformCookieEnabled(platform: Platform, enabled: Boolean)
}
