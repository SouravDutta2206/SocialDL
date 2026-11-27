package com.socialdl.src.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.model.Platform
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
        val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
        val WIFI_ONLY_DOWNLOADS = booleanPreferencesKey("wifi_only_downloads")
        val FORCE_H264_TRANSCODE = booleanPreferencesKey("force_h264_transcode")
        val STARTUP_PERMISSIONS_REQUESTED = booleanPreferencesKey("startup_permissions_requested")
        val AUDIO_FOLDER_URI = stringPreferencesKey("audio_folder_uri")
        val VIDEO_FOLDER_URI = stringPreferencesKey("video_folder_uri")
        val IMAGE_FOLDER_URI = stringPreferencesKey("image_folder_uri")
        val CHECK_AVAILABLE_STORAGE = booleanPreferencesKey("check_available_storage")
        val TRIM_FILENAMES = booleanPreferencesKey("trim_filenames")
        val MAX_CONCURRENT_FRAGMENTS = intPreferencesKey("max_concurrent_fragments")
        val USE_COOKIES = booleanPreferencesKey("use_cookies")
        val DISABLED_COOKIE_PLATFORMS = stringSetPreferencesKey("disabled_cookie_platforms")
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

    /** Writes a non-blank string under [key], or clears it when null or blank. */
    private suspend fun writeOptionalStringPreference(key: Preferences.Key<String>, value: String?) {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() }
        context.dataStore.edit { prefs ->
            if (normalized != null) {
                prefs[key] = normalized
            } else {
                prefs.remove(key)
            }
        }
    }

    override val appSettings: Flow<AppSettings> = safeData.map { prefs ->
        AppSettings(
            audioFolderUri = prefs[Keys.AUDIO_FOLDER_URI],
            videoFolderUri = prefs[Keys.VIDEO_FOLDER_URI],
            imageFolderUri = prefs[Keys.IMAGE_FOLDER_URI],
            checkAvailableStorage = prefs[Keys.CHECK_AVAILABLE_STORAGE] ?: true,
            trimFilenames = prefs[Keys.TRIM_FILENAMES] ?: false,
            wifiOnlyDownloads = prefs[Keys.WIFI_ONLY_DOWNLOADS] ?: false,
            maxConcurrentFragments = clampConcurrentFragments(prefs[Keys.MAX_CONCURRENT_FRAGMENTS]),
            maxConcurrentDownloads = clampConcurrentDownloads(prefs[Keys.MAX_CONCURRENT_DOWNLOADS]),
            forceH264Transcode = prefs[Keys.FORCE_H264_TRANSCODE] ?: false,
            useCookies = prefs[Keys.USE_COOKIES] ?: true,
            disabledCookiePlatforms = parseDisabledCookiePlatforms(prefs[Keys.DISABLED_COOKIE_PLATFORMS]),
        )
    }.distinctUntilChanged()

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

    override suspend fun setAudioFolderUri(uri: String?) {
        writeOptionalStringPreference(Keys.AUDIO_FOLDER_URI, uri)
    }

    override suspend fun setVideoFolderUri(uri: String?) {
        writeOptionalStringPreference(Keys.VIDEO_FOLDER_URI, uri)
    }

    override suspend fun setImageFolderUri(uri: String?) {
        writeOptionalStringPreference(Keys.IMAGE_FOLDER_URI, uri)
    }

    override suspend fun setCheckAvailableStorage(enabled: Boolean) {
        writePreference(Keys.CHECK_AVAILABLE_STORAGE, enabled)
    }

    override suspend fun setTrimFilenames(enabled: Boolean) {
        writePreference(Keys.TRIM_FILENAMES, enabled)
    }

    override suspend fun setMaxConcurrentFragments(max: Int) {
        writePreference(Keys.MAX_CONCURRENT_FRAGMENTS, clampConcurrentFragments(max))
    }

    override suspend fun setUseCookies(enabled: Boolean) {
        writePreference(Keys.USE_COOKIES, enabled)
    }

    override suspend fun setPlatformCookieEnabled(platform: Platform, enabled: Boolean) {
        if (platform == Platform.UNKNOWN) return
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.DISABLED_COOKIE_PLATFORMS].orEmpty()
            val updated = if (enabled) {
                current - platform.name
            } else {
                current + platform.name
            }
            prefs[Keys.DISABLED_COOKIE_PLATFORMS] = updated
        }
    }
}
