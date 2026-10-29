package com.socialdl.src.core.settings

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.socialdl.src.core.settings.model.AppTheme
import com.socialdl.src.core.settings.model.SearchEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "socialdl_settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private object Keys {
        val THEME = stringPreferencesKey("app_theme")
        val ACCENT_COLOR = intPreferencesKey("accent_color")
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val VIDEO_FOLDER_URI = stringPreferencesKey("video_folder_uri")
        val AUDIO_FOLDER_URI = stringPreferencesKey("audio_folder_uri")
        val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
        val WIFI_ONLY_DOWNLOADS = booleanPreferencesKey("wifi_only_downloads")
        val FORCE_H264_TRANSCODE = booleanPreferencesKey("force_h264_transcode")
        val STARTUP_PERMISSIONS_REQUESTED = booleanPreferencesKey("startup_permissions_requested")
    }

    override val theme: Flow<AppTheme> = context.dataStore.data.map { prefs ->
        when (prefs[Keys.THEME]) {
            AppTheme.LIGHT.name -> AppTheme.LIGHT
            AppTheme.SYSTEM.name -> AppTheme.SYSTEM
            else -> AppTheme.DARK // Default to dark per spec
        }
    }

    override val accentColor: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.ACCENT_COLOR] ?: 0xFFEF4444.toInt()
    }

    override val fallbackSearchEngine: Flow<SearchEngine> = context.dataStore.data.map { prefs ->
        when (prefs[Keys.SEARCH_ENGINE]) {
            SearchEngine.PIPED.name -> SearchEngine.PIPED
            SearchEngine.INVIDIOUS.name -> SearchEngine.INVIDIOUS
            else -> SearchEngine.YOUTUBE
        }
    }

    override val videoFolderUri: Flow<Uri?> = context.dataStore.data.map { prefs ->
        prefs[Keys.VIDEO_FOLDER_URI]?.let { Uri.parse(it) }
    }

    override val audioFolderUri: Flow<Uri?> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUDIO_FOLDER_URI]?.let { Uri.parse(it) }
    }

    override val maxConcurrentDownloads: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.MAX_CONCURRENT_DOWNLOADS] ?: 3
    }

    override val wifiOnlyDownloads: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.WIFI_ONLY_DOWNLOADS] ?: false
    }

    override val forceH264Transcode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.FORCE_H264_TRANSCODE] ?: false
    }

    override val startupPermissionsRequested: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.STARTUP_PERMISSIONS_REQUESTED] ?: false
    }

    override suspend fun setTheme(theme: AppTheme) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME] = theme.name
        }
    }

    override suspend fun setAccentColor(color: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACCENT_COLOR] = color
        }
    }

    override suspend fun setFallbackSearchEngine(engine: SearchEngine) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SEARCH_ENGINE] = engine.name
        }
    }

    override suspend fun setVideoFolderUri(uri: Uri?) {
        context.dataStore.edit { prefs ->
            if (uri != null) {
                prefs[Keys.VIDEO_FOLDER_URI] = uri.toString()
            } else {
                prefs.remove(Keys.VIDEO_FOLDER_URI)
            }
        }
    }

    override suspend fun setAudioFolderUri(uri: Uri?) {
        context.dataStore.edit { prefs ->
            if (uri != null) {
                prefs[Keys.AUDIO_FOLDER_URI] = uri.toString()
            } else {
                prefs.remove(Keys.AUDIO_FOLDER_URI)
            }
        }
    }

    override suspend fun setMaxConcurrentDownloads(max: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MAX_CONCURRENT_DOWNLOADS] = max.coerceIn(1, 10)
        }
    }

    override suspend fun setWifiOnlyDownloads(wifiOnly: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.WIFI_ONLY_DOWNLOADS] = wifiOnly
        }
    }

    override suspend fun setForceH264Transcode(force: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FORCE_H264_TRANSCODE] = force
        }
    }

    override suspend fun setStartupPermissionsRequested(requested: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.STARTUP_PERMISSIONS_REQUESTED] = requested
        }
    }
}
