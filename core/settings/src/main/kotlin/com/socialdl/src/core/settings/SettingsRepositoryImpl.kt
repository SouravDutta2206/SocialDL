package com.socialdl.src.core.settings

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.settings.model.AppTheme
import com.socialdl.src.core.settings.model.SearchEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// A corrupt preferences file (power loss / partial write) must not crash the app
// forever: replace it with empty preferences and let the safe reader fall back.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "socialdl_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private object Keys {
        val THEME = stringPreferencesKey("app_theme")
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val VIDEO_FOLDER_URI = stringPreferencesKey("video_folder_uri")
        val AUDIO_FOLDER_URI = stringPreferencesKey("audio_folder_uri")
        val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
        val WIFI_ONLY_DOWNLOADS = booleanPreferencesKey("wifi_only_downloads")
        val FORCE_H264_TRANSCODE = booleanPreferencesKey("force_h264_transcode")
        val STARTUP_PERMISSIONS_REQUESTED = booleanPreferencesKey("startup_permissions_requested")
    }

    /**
     * One shared reader: an IOException (corrupt/unreadable file) degrades to
     * defaults instead of crashing every collector, and unchanged preferences do
     * not re-emit (which used to restart theme/search observers on every write).
     */
    private val safeData: Flow<Preferences> = context.dataStore.data.catch { error ->
        if (error is IOException) {
            Logger.e("SettingsRepository", "Failed reading settings DataStore", error)
            emit(emptyPreferences())
        } else {
            throw error
        }
    }

    override val theme: Flow<AppTheme> = safeData.map { prefs ->
        when (prefs[Keys.THEME]) {
            AppTheme.LIGHT.name -> AppTheme.LIGHT
            AppTheme.SYSTEM.name -> AppTheme.SYSTEM
            else -> AppTheme.DARK // Default to dark per spec
        }
    }.distinctUntilChanged()

    override val fallbackSearchEngine: Flow<SearchEngine> = safeData.map { prefs ->
        when (prefs[Keys.SEARCH_ENGINE]) {
            SearchEngine.PIPED.name -> SearchEngine.PIPED
            SearchEngine.INVIDIOUS.name -> SearchEngine.INVIDIOUS
            else -> SearchEngine.YOUTUBE
        }
    }.distinctUntilChanged()

    override val videoFolderUri: Flow<Uri?> = safeData.map { prefs ->
        prefs[Keys.VIDEO_FOLDER_URI]?.let { Uri.parse(it) }
    }.distinctUntilChanged()

    override val audioFolderUri: Flow<Uri?> = safeData.map { prefs ->
        prefs[Keys.AUDIO_FOLDER_URI]?.let { Uri.parse(it) }
    }.distinctUntilChanged()

    override val maxConcurrentDownloads: Flow<Int> = safeData.map { prefs ->
        prefs[Keys.MAX_CONCURRENT_DOWNLOADS] ?: 3
    }.distinctUntilChanged()

    override val wifiOnlyDownloads: Flow<Boolean> = safeData.map { prefs ->
        prefs[Keys.WIFI_ONLY_DOWNLOADS] ?: false
    }.distinctUntilChanged()

    override val forceH264Transcode: Flow<Boolean> = safeData.map { prefs ->
        prefs[Keys.FORCE_H264_TRANSCODE] ?: false
    }.distinctUntilChanged()

    override val startupPermissionsRequested: Flow<Boolean> = safeData.map { prefs ->
        prefs[Keys.STARTUP_PERMISSIONS_REQUESTED] ?: false
    }.distinctUntilChanged()

    override suspend fun setTheme(theme: AppTheme) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME] = theme.name
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
