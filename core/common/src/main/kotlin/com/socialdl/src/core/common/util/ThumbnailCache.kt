package com.socialdl.src.core.common.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches remote thumbnails locally (§4.3) so the format sheet and Downloads history
 * retain valid image references even if the source URL later expires or is deleted.
 *
 * Hardening:
 * - bounded download (declared and streamed), image content-type check;
 * - exact cache-path lookup instead of an O(n) directory scan;
 * - access-time touch on read (real LRU, not oldest-written);
 * - admission-time eviction, startup cleanup of stale `.tmp` files;
 * - reference-counted per-URL locks so the map cannot grow without bound.
 */
@Singleton
open class ThumbnailCache @Inject constructor(
    // Nullable only as a JVM-test seam (fakes pass null and override store());
    // Dagger always supplies the real application context.
    @ApplicationContext private val context: Context?,
    httpClient: OkHttpClient,
) {
    private val tag = "ThumbnailCache"

    // Thumbnails keep their own, shorter timeouts.
    private val thumbnailClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    // 50MB LRU cap; internal var so tests can shrink it.
    internal var maxCacheSizeBytes = 50L * 1024L * 1024L

    /** Test seam (same module only): points the cache at a scratch directory. */
    internal fun useDirectoryForTest(directory: File) {
        explicitDirectory = directory
    }

    private var explicitDirectory: File? = null

    private class LockEntry {
        val mutex = Mutex()
        var references = 0
    }

    // Per-hash lock: concurrent store() calls for the same URL serialize on the lock,
    // while different URLs proceed in parallel. Entries are ref-counted and removed.
    private val urlLocks = ConcurrentHashMap<String, LockEntry>()

    /** Serializes eviction so two stores cannot delete each other's fresh files. */
    private val evictionLock = Any()

    private val dir: File by lazy {
        (explicitDirectory ?: File(getRealDir(), "thumbnails")).apply {
            mkdirs()
            deleteStaleTempFiles(this)
        }
    }

    /**
     * Recreates the cache directory when the OS cleared it mid-session. Without
     * this a store() after a cache clear failed with ENOENT until app restart.
     */
    private fun ensureDir(): File = dir.apply { if (!exists()) mkdirs() }

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
        // Fast path: already cached. findCached touches mtime for real LRU order.
        findCached(hash)?.let { existing ->
            return@withContext "file://${existing.absolutePath}"
        }

        val entry = acquireLock(hash)
        try {
            return@withContext entry.mutex.withLock {
                // Re-check under the lock: another caller may have stored it while we raced.
                findCached(hash)?.let { cachedNow ->
                    return@withLock "file://${cachedNow.absolutePath}"
                }

                // Clearing the OS cache deletes this directory mid-session.
                ensureDir()
                val tmp = File(dir, "$hash.tmp")
                try {
                    val request = Request.Builder()
                        .url(sourceUrl)
                        .header("Accept", "image/*")
                        .build()
                    val call = thumbnailClient.newCall(request)
                    // A blocking OkHttp execute() ignores coroutine cancellation;
                    // cancel the call when the caller's job is cancelled so a
                    // screen exit never holds the per-URL mutex on a dead request.
                    val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                        if (cause != null) call.cancel()
                    }
                    try {
                        call.execute().use { response ->
                            currentCoroutineContext().ensureActive()
                            if (!response.isSuccessful) return@withLock sourceUrl
                            val body = response.body ?: return@withLock sourceUrl

                            val mime = body.contentType()?.toString()?.substringBefore(';')?.trim().orEmpty()
                            if (mime.isNotEmpty() && !mime.startsWith("image/", ignoreCase = true)) {
                                Logger.w(tag, "Thumbnail rejected: content-type '$mime' for $sourceUrl")
                                return@withLock sourceUrl
                            }

                            val declaredLength = body.contentLength()
                            if (declaredLength > MAX_THUMBNAIL_BYTES) {
                                Logger.w(tag, "Thumbnail rejected: declared $declaredLength bytes for $sourceUrl")
                                return@withLock sourceUrl
                            }

                            ensureDir()
                            val cachedFile = File(dir, "$hash.${extensionFor(mime, sourceUrl)}")
                            val written = body.byteStream().use { input ->
                                FileOutputStream(tmp).use { output -> copyBounded(input, output, MAX_THUMBNAIL_BYTES) }
                            }
                            currentCoroutineContext().ensureActive()
                            if (written <= 0L) {
                                tmp.delete()
                                return@withLock sourceUrl
                            }
                            if (tmp.renameTo(cachedFile)) {
                                // Never evict the file this store just admitted.
                                enforceCacheCap(protectedFile = cachedFile)
                                return@withLock "file://${cachedFile.absolutePath}"
                            }
                            if (cachedFile.exists()) {
                                tmp.delete()
                                return@withLock "file://${cachedFile.absolutePath}"
                            }
                        }
                    } finally {
                        cancelHandle?.dispose()
                    }
                } catch (e: CancellationException) {
                    // Structured cancellation is never a cache miss.
                    throw e
                } catch (e: Exception) {
                    // OkHttp cancel() or stream interrupts surface as IOException("Canceled").
                    currentCoroutineContext().ensureActive()
                    Logger.w(tag, "Failed to download thumbnail $sourceUrl: ${e.message}")
                } finally {
                    runCatching { tmp.delete() }
                }

                sourceUrl
            }
        } finally {
            releaseLock(hash, entry)
        }
    }

    /**
     * Evicts oldest-accessed non-temp files until the cache fits the cap.
     *
     * @param protectedFile the file a concurrent store just admitted; eviction
     *   skips it so the caller's own cap check can never delete its result.
     */
    internal fun enforceCacheCap(protectedFile: File? = null) {
        synchronized(evictionLock) {
            try {
                // Never evict a .tmp file: it may belong to an in-flight download.
                val files = dir.listFiles { _, name -> !name.endsWith(".tmp") } ?: return
                var totalSize = files.sumOf { it.length() }
                if (totalSize <= maxCacheSizeBytes) return

                val protectedPath = protectedFile?.absolutePath
                val sortedByOldestAccess = files.sortedBy { it.lastModified() }
                for (file in sortedByOldestAccess) {
                    if (totalSize <= maxCacheSizeBytes) break
                    if (protectedPath != null && file.absolutePath == protectedPath) continue
                    val size = file.length()
                    if (file.delete()) {
                        totalSize -= size
                    }
                }
            } catch (e: Exception) {
                Logger.w(tag, "Error enforcing thumbnail cache cap: ${e.message}")
            }
        }
    }

    /** Exact path candidates — no directory scan on every lookup. */
    private fun findCached(hash: String): File? {
        for (ext in CACHE_EXTENSIONS) {
            val file = File(dir, "$hash.$ext")
            if (file.isFile && file.length() > 0) {
                // Touch for access-time LRU; ignore filesystem that refuses it.
                runCatching { file.setLastModified(System.currentTimeMillis()) }
                return file
            }
        }
        return null
    }

    private fun acquireLock(hash: String): LockEntry =
        urlLocks.compute(hash) { _, existing ->
            (existing ?: LockEntry()).also { it.references++ }
        }!!

    private fun releaseLock(hash: String, entry: LockEntry) {
        urlLocks.computeIfPresent(hash) { _, current ->
            if (current !== entry) return@computeIfPresent current
            current.references--
            if (current.references <= 0) null else current
        }
    }

    private fun extensionFor(mime: String, sourceUrl: String): String = when {
        mime.contains("png", true) || sourceUrl.contains(".png", true) -> "png"
        mime.contains("webp", true) || sourceUrl.contains(".webp", true) -> "webp"
        mime.contains("gif", true) || sourceUrl.contains(".gif", true) -> "gif"
        else -> "jpg"
    }

    /** Copies at most [maxBytes]; returns -1 when the response exceeds the cap. */
    private suspend fun copyBounded(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        maxBytes: Long
    ): Long {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            // Cancellation point for a blocking body read.
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return -1L
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun deleteStaleTempFiles(directory: File) {
        val cutoff = System.currentTimeMillis() - STALE_TEMP_MS
        directory.listFiles { _, name -> name.endsWith(".tmp") }?.forEach { tmp ->
            if (tmp.lastModified() < cutoff) runCatching { tmp.delete() }
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_THUMBNAIL_BYTES = 5L * 1024L * 1024L
        const val STALE_TEMP_MS = 60L * 60L * 1000L
        val CACHE_EXTENSIONS = listOf("jpg", "png", "webp", "gif")
    }
}
