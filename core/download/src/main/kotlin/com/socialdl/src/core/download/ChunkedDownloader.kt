package com.socialdl.src.core.download

import com.socialdl.src.core.common.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles network streaming and chunked range downloads with exponential backoff.
 *
 * Resume is intentionally OUT of scope (§9): a download starts from an empty
 * destination file, and a worker re-run after process death/system stop
 * restarts the file from byte 0. Retry/backoff exists only within a single
 * chunk's attempt loop, never across runs.
 */
@Singleton
class ChunkedDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val tag = "ChunkedDownloader"

    private val streamingClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // A media download is a continuous stream: any 15s with ZERO bytes
            // means the path is dead (wifi "associated" but unroutable, dead
            // radio, dropped NAT mapping). Waiting minutes for a huge socket
            // timeout is what produced the "download hangs on network loss"
            // state; the watchdog handles radio loss instantly, this handles
            // the silently-stalled cases it can't see.
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        const val DEFAULT_CHUNK_SIZE = 10L * 1024L * 1024L // 10 MB default for YouTube/googlevideo
        private const val MAX_RETRIES_PER_CHUNK = 5
    }

    /**
     * Downloads a file using chunked range requests if chunkSizeBytes > 0 or for YouTube,
     * otherwise falls back to a continuous single stream.
     */
    suspend fun download(
        url: String,
        destination: File,
        chunkSizeBytes: Long,
        platform: String,
        isStopped: () -> Boolean,
        canceller: DownloadCallCanceller? = null,
        onProgress: suspend (bytesWritten: Long, totalBytes: Long?) -> Unit
    ) {
        val effectiveChunkSize = when {
            chunkSizeBytes > 0L -> chunkSizeBytes
            platform.equals("YOUTUBE", ignoreCase = true) || url.contains("googlevideo.com") -> DEFAULT_CHUNK_SIZE
            else -> 0L
        }

        if (effectiveChunkSize > 0L) {
            downloadChunked(url, destination, effectiveChunkSize, isStopped, canceller, onProgress)
        } else {
            downloadSingleStream(url, destination, isStopped, canceller, onProgress)
        }
    }

    private suspend fun downloadChunked(
        url: String,
        destination: File,
        chunkSize: Long,
        isStopped: () -> Boolean,
        canceller: DownloadCallCanceller?,
        onProgress: suspend (bytesWritten: Long, totalBytes: Long?) -> Unit
    ) {
        if (destination.exists()) {
            destination.delete()
        }
        destination.createNewFile()

        var downloadedBytes = 0L
        var totalBytes: Long? = null

        while (totalBytes == null || downloadedBytes < totalBytes) {
            if (isStopped()) {
                throw CancellationException("Download was stopped")
            }

            val chunkStart = downloadedBytes
            val chunkEnd = if (totalBytes != null) {
                minOf(chunkStart + chunkSize - 1, totalBytes - 1)
            } else {
                chunkStart + chunkSize - 1
            }

            var chunkSuccess = false
            var chunkAttempts = 0
            var lastError: Exception? = null

            while (!chunkSuccess && chunkAttempts < MAX_RETRIES_PER_CHUNK) {
                if (isStopped()) {
                    throw CancellationException("Download was stopped")
                }
                chunkAttempts++
                try {
                    val call = streamingClient.newCall(
                        Request.Builder()
                            .url(url)
                            .header("User-Agent", DEFAULT_USER_AGENT)
                            .header("Accept-Encoding", "identity")
                            .header("Range", "bytes=$chunkStart-$chunkEnd")
                            .build()
                    )
                    // Registered so the worker can abort the blocked read the instant
                    // the network drops instead of waiting out the read timeout.
                    canceller?.register(call)

                    call.execute().use { response ->
                        if (response.code == 416) {
                            if (totalBytes == null) {
                                // Unknown size and the server says the range is
                                // past EOF: everything up to chunkStart is all
                                // the server has — treat it as complete.
                                Logger.d(tag, "HTTP 416 Range Not Satisfiable at byte $chunkStart. Marking complete.")
                                chunkSuccess = true
                                totalBytes = chunkStart
                                return@use
                            }
                            // totalBytes was already resolved but the remaining
                            // range is unsatisfiable: the file is truncated /
                            // content changed underneath us. Retrying is pointless
                            // and completing would silently persist a truncated
                            // file — fail the download.
                            throw RangeNotSatisfiableException(
                                "HTTP error 416: Range Not Satisfiable at byte $chunkStart of resolved total=$totalBytes"
                            )
                        }

                        if (response.code == 200 && chunkStart == 0L) {
                            Logger.w(tag, "Server returned 200 OK without Range support. Falling back to single stream.")
                            val body = response.body ?: throw IOException("Empty response body from $url")
                            val cl = body.contentLength().takeIf { it > 0 }
                            totalBytes = cl
                            body.byteStream().use { input ->
                                FileOutputStream(destination, false).use { output ->
                                    copyStreamWithProgress(input, output, cl, isStopped, onProgress)
                                }
                            }
                            chunkSuccess = true
                            downloadedBytes = destination.length()
                            return@use
                        }

                        if (response.code == 200 && chunkStart > 0L) {
                            // Server stopped honoring Range mid-download (or never
                            // honored it for this URL). Retrying the same request
                            // yields the same 200 — fail fast instead of burning
                            // backoff cycles and full-body connection discards.
                            Logger.w(tag, "Server returned 200 OK for ranged request at byte $chunkStart. Aborting.")
                            throw RangeNotSupportedException("HTTP error 200: server ignored the Range request at byte $chunkStart")
                        }

                        if (response.code != 206) {
                            throw IOException("HTTP error ${response.code}: ${response.message}")
                        }

                        val body = response.body ?: throw IOException("Empty response body for chunk $chunkStart-$chunkEnd")

                        if (totalBytes == null) {
                            val contentRange = response.header("Content-Range")
                            totalBytes = parseTotalBytesFromContentRange(contentRange)
                            Logger.d(tag, "Resolved totalBytes=$totalBytes from Content-Range: $contentRange")
                        }

                        val expectedBytes = chunkEnd - chunkStart + 1
                        var chunkBytesRead = 0L

                        FileOutputStream(destination, true).use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                var bytesRead: Int
                                var lastProgressReport = System.currentTimeMillis()

                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    if (isStopped()) throw CancellationException("Download was stopped")
                                    output.write(buffer, 0, bytesRead)
                                    chunkBytesRead += bytesRead
                                    downloadedBytes += bytesRead

                                    val now = System.currentTimeMillis()
                                    if (now - lastProgressReport > 200) {
                                        onProgress(downloadedBytes, totalBytes)
                                        lastProgressReport = now
                                    }
                                }
                                onProgress(downloadedBytes, totalBytes)
                            }
                        }

                        if (totalBytes == null && chunkBytesRead < expectedBytes) {
                            totalBytes = downloadedBytes
                            Logger.d(tag, "Reached EOF without Content-Range: totalBytes=$totalBytes")
                        }

                        chunkSuccess = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RangeNotSatisfiableException) {
                    throw e // server cannot serve the remaining range — retrying cannot fix it
                } catch (e: RangeNotSupportedException) {
                    throw e // server ignores Range — retrying cannot fix it
                } catch (e: Exception) {
                    if (canceller?.isCancelled == true) {
                        // The watchdog aborted us (network loss): retrying against a
                        // dead network is pointless — fail the download immediately.
                        throw e
                    }
                    lastError = e
                    Logger.w(tag, "Chunk $chunkStart-$chunkEnd failed (attempt $chunkAttempts/$MAX_RETRIES_PER_CHUNK): ${e.message}")
                    if (chunkAttempts < MAX_RETRIES_PER_CHUNK) {
                        val backoffMs = (1000L * (1 shl (chunkAttempts - 1))).coerceAtMost(10000L)
                        delay(backoffMs)
                        truncateFile(destination, chunkStart)
                        downloadedBytes = chunkStart
                    }
                }
            }

            if (!chunkSuccess) {
                throw IOException("Failed to download chunk $chunkStart-$chunkEnd after $MAX_RETRIES_PER_CHUNK attempts", lastError)
            }
        }
    }

    private suspend fun downloadSingleStream(
        url: String,
        destination: File,
        isStopped: () -> Boolean,
        canceller: DownloadCallCanceller?,
        onProgress: suspend (bytesWritten: Long, totalBytes: Long?) -> Unit
    ) {
        val call = streamingClient.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .build()
        )
        canceller?.register(call)

        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP error ${response.code}: ${response.message}")
            }

            val body = response.body ?: throw IOException("Empty response body from $url")
            val contentLength = body.contentLength().takeIf { it > 0 }

            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    copyStreamWithProgress(input, output, contentLength, isStopped, onProgress)
                }
            }
        }
    }

    private suspend fun copyStreamWithProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        totalBytes: Long?,
        isStopped: () -> Boolean,
        onProgress: suspend (bytesWritten: Long, totalBytes: Long?) -> Unit
    ) {
        val buffer = ByteArray(64 * 1024)
        var bytesRead: Int
        var totalRead = 0L
        var lastProgressReport = 0L

        while (input.read(buffer).also { bytesRead = it } != -1) {
            if (isStopped()) throw CancellationException("Download was stopped")
            output.write(buffer, 0, bytesRead)
            totalRead += bytesRead

            val now = System.currentTimeMillis()
            if (now - lastProgressReport > 200) {
                onProgress(totalRead, totalBytes)
                lastProgressReport = now
            }
        }
        onProgress(totalRead, totalBytes)
    }

    private fun truncateFile(file: File, targetSize: Long) {
        if (file.exists() && file.length() > targetSize) {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(targetSize)
            }
        }
    }

    private fun parseTotalBytesFromContentRange(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        val totalPart = contentRange.substringAfterLast('/', "")
        return if (totalPart == "*") null else totalPart.trim().toLongOrNull()
    }
}

/** 416 on a range the resolved total says must exist — the file cannot be completed. */
private class RangeNotSatisfiableException(message: String) : IOException(message)

/** Server returned 200 for a ranged (non-zero-offset) request — Range support is gone. */
private class RangeNotSupportedException(message: String) : IOException(message)
