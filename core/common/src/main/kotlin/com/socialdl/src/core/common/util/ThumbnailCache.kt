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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches remote thumbnails locally (§4.3) so the format sheet and Downloads history
 * retain valid image references even if the source URL later expires or is deleted.
 */
@Singleton
open class ThumbnailCache(
    private val cacheDirectory: File?
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.cacheDir, "thumbnails"))

    constructor() : this(null as File?)

    private val tag = "ThumbnailCache"
    private val maxCacheSizeBytes = 50L * 1024L * 1024L // 50MB LRU cap

    private val dir: File by lazy {
        (cacheDirectory ?: File(System.getProperty("java.io.tmpdir"), "socialdl_thumbnails")).apply { mkdirs() }
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
        val existing = dir.listFiles { _, name -> name.startsWith(hash) && !name.endsWith(".tmp") }?.firstOrNull()
        if (existing != null && existing.length() > 0) {
            return@withContext "file://${existing.absolutePath}"
        }

        try {
            val url = URL(sourceUrl)
            val connection = url.openConnection() as HttpURLConnection
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
                val tempFile = File(dir, "$hash.tmp")
                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (tempFile.renameTo(cachedFile)) {
                    enforceCacheCap()
                    return@withContext "file://${cachedFile.absolutePath}"
                } else if (cachedFile.exists()) {
                    tempFile.delete()
                    return@withContext "file://${cachedFile.absolutePath}"
                }
            }
        } catch (e: Exception) {
            Logger.w(tag, "Failed to download thumbnail $sourceUrl: ${e.message}")
        }

        sourceUrl
    }

    private fun enforceCacheCap() {
        try {
            val files = dir.listFiles() ?: return
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
