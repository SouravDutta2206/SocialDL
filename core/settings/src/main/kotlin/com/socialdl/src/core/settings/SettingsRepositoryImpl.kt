package com.socialdl.src.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.socialdl.src.core.common.util.Logger
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

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private object Keys {
        val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
        val WIFI_ONLY_DOWNLOADS = booleanPreferencesKey("wifi_only_downloads")
        val FORCE_H264_TRANSCODE = booleanPreferencesKey("force_h264_transcode")
        val STARTUP_PERMISSIONS_REQUESTED = booleanPreferencesKey("startup_permissions_requested")
    }

    /**
     * One shared reader: an IOException (corrupt/unreadable file) degrades to
     * defaults instead of crashing every collector.
     */
    private val safeData: Flow<Preferences> = context.dataStore.data.catch { error ->
        if (error is IOException) {
            Logger.e("SettingsRepository", "Failed reading settings DataStore", error)
            emit(emptyPreferences())
        } else {
            throw error
        }
    }

    /** Reads [key] through [transform]; unchanged values do not re-emit. */
    private fun <T> preference(key: Preferences.Key<T>, transform: (T?) -> T): Flow<T> =
        safeData.map { prefs -> transform(prefs[key]) }.distinctUntilChanged()

    /** Writes [value] under [key] on the shared DataStore. */
    private suspend fun <T> writePreference(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { prefs ->
            prefs[key] = value
        }
    }

    override val maxConcurrentDownloads: Flow<Int> =
        preference(Keys.MAX_CONCURRENT_DOWNLOADS, ::clampConcurrentDownloads)

    override val wifiOnlyDownloads: Flow<Boolean> =
        preference(Keys.WIFI_ONLY_DOWNLOADS) { it ?: false }

    override val forceH264Transcode: Flow<Boolean> =
        preference(Keys.FORCE_H264_TRANSCODE) { it ?: false }

    override val startupPermissionsRequested: Flow<Boolean> =
        preference(Keys.STARTUP_PERMISSIONS_REQUESTED) { it ?: false }

    override suspend fun setMaxConcurrentDownloads(max: Int) {
        writePreference(Keys.MAX_CONCURRENT_DOWNLOADS, clampConcurrentDownloads(max))
    }

    override suspend fun setWifiOnlyDownloads(wifiOnly: Boolean) {
        writePreference(Keys.WIFI_ONLY_DOWNLOADS, wifiOnly)
    }

    override suspend fun setForceH264Transcode(force: Boolean) {
        writePreference(Keys.FORCE_H264_TRANSCODE, force)
    }

    override suspend fun setStartupPermissionsRequested(requested: Boolean) {
        writePreference(Keys.STARTUP_PERMISSIONS_REQUESTED, requested)
    }
}
