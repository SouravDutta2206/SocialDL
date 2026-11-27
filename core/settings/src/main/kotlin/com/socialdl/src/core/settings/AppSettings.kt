package com.socialdl.src.core.settings

import com.socialdl.src.core.model.Platform
import java.net.URLDecoder

// Concurrency bounds shared between DataStore normalization and the settings UI.
const val MIN_CONCURRENT_DOWNLOADS = 1
const val MAX_CONCURRENT_DOWNLOADS = 10
const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 2

const val MIN_CONCURRENT_FRAGMENTS = 1
const val MAX_CONCURRENT_FRAGMENTS = 10
const val DEFAULT_MAX_CONCURRENT_FRAGMENTS = 3

const val DEFAULT_AUDIO_FOLDER_RELATIVE = "Download/SocialDL/audios"
const val DEFAULT_VIDEO_FOLDER_RELATIVE = "Download/SocialDL/videos"
const val DEFAULT_IMAGE_FOLDER_RELATIVE = "Download/SocialDL/images"
const val DEFAULT_INTERNAL_STORAGE_ROOT = "/storage/emulated/0"

/**
 * Snapshot of all user-configurable settings backed by DataStore.
 */
data class AppSettings(
    // Folders
    val audioFolderUri: String? = null,
    val videoFolderUri: String? = null,
    val imageFolderUri: String? = null,
    val checkAvailableStorage: Boolean = true,
    val trimFilenames: Boolean = false,
    // Downloads
    val wifiOnlyDownloads: Boolean = false,
    val maxConcurrentFragments: Int = DEFAULT_MAX_CONCURRENT_FRAGMENTS,
    val maxConcurrentDownloads: Int = DEFAULT_MAX_CONCURRENT_DOWNLOADS,
    val forceH264Transcode: Boolean = false,
    // Cookies
    val useCookies: Boolean = true,
    val disabledCookiePlatforms: Set<Platform> = emptySet(),
) {
    fun isPlatformCookieEnabled(platform: Platform): Boolean =
        platform !in disabledCookiePlatforms
}

/** Clamps a stored/supplied download worker count into the supported range. */
fun clampConcurrentDownloads(value: Int?): Int =
    (value ?: DEFAULT_MAX_CONCURRENT_DOWNLOADS)
        .coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS)

/** Clamps a stored/supplied fragment concurrency count into the supported range. */
fun clampConcurrentFragments(value: Int?): Int =
    (value ?: DEFAULT_MAX_CONCURRENT_FRAGMENTS)
        .coerceIn(MIN_CONCURRENT_FRAGMENTS, MAX_CONCURRENT_FRAGMENTS)

/** Maps persisted platform enum names safely, ignoring unrecognized or UNKNOWN entries. */
fun parseDisabledCookiePlatforms(raw: Set<String>?): Set<Platform> {
    if (raw.isNullOrEmpty()) return emptySet()
    val byName = Platform.entries.associateBy { it.name }
    return raw.mapNotNullTo(LinkedHashSet()) { name ->
        byName[name]?.takeIf { it != Platform.UNKNOWN }
    }
}

/**
 * Formats a persisted SAF tree URI or filesystem path for display in Folders settings,
 * falling back to `{internalStorageRoot}/{defaultRelativePath}` when unset.
 */
fun formatFolderDisplayPath(
    savedUriOrPath: String?,
    defaultRelativePath: String,
    internalStorageRoot: String = DEFAULT_INTERNAL_STORAGE_ROOT,
): String {
    val root = internalStorageRoot.trimEnd('/').ifEmpty { DEFAULT_INTERNAL_STORAGE_ROOT }
    if (savedUriOrPath.isNullOrBlank()) {
        return "$root/$defaultRelativePath"
    }
    val trimmed = savedUriOrPath.trim()
    if (trimmed.startsWith("content://") && trimmed.contains("/tree/")) {
        val rawTreeSegment = trimmed
            .substringAfter("/tree/")
            .substringBefore('/')
            .substringBefore('?')
        val decodedTree = runCatching { URLDecoder.decode(rawTreeSegment, "UTF-8") }
            .getOrDefault(rawTreeSegment)
        if (decodedTree.startsWith("primary:")) {
            val relative = decodedTree.removePrefix("primary:").trimStart('/')
            return if (relative.isEmpty()) root else "$root/$relative"
        }
        return decodedTree.ifBlank { "$root/$defaultRelativePath" }
    }
    return trimmed
}
