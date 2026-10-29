package com.socialdl.src.core.settings

import android.net.Uri
import com.socialdl.src.core.settings.model.AppTheme
import com.socialdl.src.core.settings.model.SearchEngine
import kotlinx.coroutines.flow.Flow

/**
 * Settings repository contract per §2.7.
 * Backed by Jetpack DataStore preferences.
 */
interface SettingsRepository {
    val theme: Flow<AppTheme>
    val accentColor: Flow<Int>
    val fallbackSearchEngine: Flow<SearchEngine>
    val videoFolderUri: Flow<Uri?>
    val audioFolderUri: Flow<Uri?>
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

    suspend fun setTheme(theme: AppTheme)
    suspend fun setAccentColor(color: Int)
    suspend fun setFallbackSearchEngine(engine: SearchEngine)
    suspend fun setVideoFolderUri(uri: Uri?)
    suspend fun setAudioFolderUri(uri: Uri?)
    suspend fun setMaxConcurrentDownloads(max: Int)
    suspend fun setWifiOnlyDownloads(wifiOnly: Boolean)
    suspend fun setForceH264Transcode(force: Boolean)
    suspend fun setStartupPermissionsRequested(requested: Boolean)
}
