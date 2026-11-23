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

// Worker-count bounds. A persisted value outside this range (old build, manual
// DataStore edit) must never reach the download scheduler.
internal const val MIN_CONCURRENT_DOWNLOADS = 1
internal const val MAX_CONCURRENT_DOWNLOADS = 10
internal const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 3

/** Clamps a stored/supplied worker count into the supported range. */
internal fun clampConcurrentDownloads(value: Int?): Int =
    (value ?: DEFAULT_MAX_CONCURRENT_DOWNLOADS)
        .coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS)

/** Trims a stored folder URI string; blank strings mean "not configured". */
internal fun normalizeFolderUriString(raw: String?): String? =
    raw?.trim()?.takeIf { it.isNotEmpty() }

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
        normalizeFolderUriString(prefs[Keys.VIDEO_FOLDER_URI])
            ?.let { Uri.parse(it) }
            ?.takeUnless { it == Uri.EMPTY }
    }.distinctUntilChanged()

    override val audioFolderUri: Flow<Uri?> = safeData.map { prefs ->
        normalizeFolderUriString(prefs[Keys.AUDIO_FOLDER_URI])
            ?.let { Uri.parse(it) }
            ?.takeUnless { it == Uri.EMPTY }
    }.distinctUntilChanged()

    override val maxConcurrentDownloads: Flow<Int> = safeData.map { prefs ->
        clampConcurrentDownloads(prefs[Keys.MAX_CONCURRENT_DOWNLOADS])
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
        setFolderUri(Keys.VIDEO_FOLDER_URI, uri)
    }

    override suspend fun setAudioFolderUri(uri: Uri?) {
        setFolderUri(Keys.AUDIO_FOLDER_URI, uri)
    }

    /** Stores a usable document-tree URI; null/empty/blank input clears the key. */
    private suspend fun setFolderUri(key: Preferences.Key<String>, uri: Uri?) {
        context.dataStore.edit { prefs ->
            if (uri == null || uri == Uri.EMPTY || uri.toString().isBlank()) {
                prefs.remove(key)
            } else {
                prefs[key] = uri.toString()
            }
        }
    }

    override suspend fun setMaxConcurrentDownloads(max: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MAX_CONCURRENT_DOWNLOADS] = clampConcurrentDownloads(max)
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
