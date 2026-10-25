package com.socialdl.src.core.common.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches remote thumbnails locally (§4.3) so the format sheet and Downloads history
 * retain valid image references even if the source URL later expires or is deleted.
 */
@Singleton
open class ThumbnailCache @Inject constructor(
    // Nullable only as a JVM-test seam (fakes pass null and override store());
    // Dagger always supplies the real application context.
    @ApplicationContext private val context: Context?
) {
    private val tag = "ThumbnailCache"
    // 50MB LRU cap; internal var so tests can shrink it.
    internal var maxCacheSizeBytes = 50L * 1024L * 1024L

    /** Test seam (same module only): points the cache at a scratch directory. */
    internal fun useDirectoryForTest(directory: File) {
        explicitDirectory = directory
    }

    private var explicitDirectory: File? = null

    // Per-hash lock: concurrent store() calls for the same URL serialize on the lock,
    // while different URLs proceed in parallel.
    private val urlLocks = ConcurrentHashMap<String, Any>()

    private val dir: File by lazy {
        (explicitDirectory ?: File(getRealDir(), "thumbnails")).apply { mkdirs() }
    }

    private fun getRealDir(): File {
        val ctx = requireNotNull(context) { "ThumbnailCache (test fake, no context) had dir accessed" }
        return ctx.cacheDir
    }

    /**
     * Downloads and stores a thumbnail in local cache, returning a file:// URI.
     * If download fails, returns the original remote URL as fallback.
     */
    open suspend fun store(sourceUrl: String?): String? = withContext(Dispatchers.IO) {
        if (sourceUrl.isNullOrBlank()) return@withContext null
        if (sourceUrl.startsWith("file://") || sourceUrl.startsWith("content://")) {
            return@withContext sourceUrl
        }

        val hash = sha256(sourceUrl)
        // Fast path: already cached. The lock section re-checks to guard the race
        // where two callers both miss the cache for the same URL.
        val existing = dir.listFiles { _, name -> name.startsWith(hash) && !name.endsWith(".tmp") }?.firstOrNull()
        if (existing != null && existing.length() > 0) {
            return@withContext "file://${existing.absolutePath}"
        }

        val lock = urlLocks.computeIfAbsent(hash) { Any() }
        return@withContext synchronized(lock) {
            // Re-check under the lock: another caller may have stored it while we were racing.
            val cachedNow = dir.listFiles { _, name -> name.startsWith(hash) && !name.endsWith(".tmp") }?.firstOrNull()
            if (cachedNow != null && cachedNow.length() > 0) {
                return@synchronized "file://${cachedNow.absolutePath}"
            }

            var connection: HttpURLConnection? = null
            var tempFile: File? = null
            try {
                val url = URL(sourceUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = true
                connection.connect()

                if (connection.responseCode in 200..299) {
                    val mime = connection.contentType?.substringBefore(';') ?: ""
                    val ext = when {
                        mime.contains("png", ignoreCase = true) || sourceUrl.contains(".png", ignoreCase = true) -> "png"
                        mime.contains("webp", ignoreCase = true) || sourceUrl.contains(".webp", ignoreCase = true) -> "webp"
                        mime.contains("gif", ignoreCase = true) || sourceUrl.contains(".gif", ignoreCase = true) -> "gif"
                        else -> "jpg"
                    }
                    val cachedFile = File(dir, "$hash.$ext")
                    val tmp = File(dir, "$hash.tmp")
                    tempFile = tmp
                    connection.inputStream.use { input ->
                        FileOutputStream(tmp).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tmp.renameTo(cachedFile)) {
                        enforceCacheCap()
                        return@synchronized "file://${cachedFile.absolutePath}"
                    } else if (cachedFile.exists()) {
                        return@synchronized "file://${cachedFile.absolutePath}"
                    }
                }
            } catch (e: Exception) {
                Logger.w(tag, "Failed to download thumbnail $sourceUrl: ${e.message}")
            } finally {
                runCatching { connection?.disconnect() }
                // renameTo() already consumed the temp file, so this is a no-op on success.
                tempFile?.delete()
            }

            sourceUrl
        }
    }

    internal fun enforceCacheCap() {
        try {
            // Never evict a .tmp file: it may belong to an in-flight download.
            val files = dir.listFiles { _, name -> !name.endsWith(".tmp") } ?: return
            var totalSize = files.sumOf { it.length() }
            if (totalSize <= maxCacheSizeBytes) return

            val sortedByOldest = files.sortedBy { it.lastModified() }
            for (file in sortedByOldest) {
                if (totalSize <= maxCacheSizeBytes) break
                val size = file.length()
                if (file.delete()) {
                    totalSize -= size
                }
            }
        } catch (e: Exception) {
            Logger.w(tag, "Error enforcing thumbnail cache cap: ${e.message}")
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
