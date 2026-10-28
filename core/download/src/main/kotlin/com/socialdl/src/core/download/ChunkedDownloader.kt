package com.socialdl.src.core.download

import com.socialdl.src.core.common.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
 *
 * Chunked 206 responses are validated against their own `Content-Range`:
 * - `Content-Range` must start at the requested offset and end no later than the
 *   requested end. Its claimed length (`end - start + 1`) must match both the
 *   response `Content-Length` (when present) and the bytes actually read.
 * - The first resolved total is pinned for the rest of the download. Later
 *   responses may omit it (`*`) but may not change it.
 * - A strong `ETag` is pinned and sent as `If-Range` on later requests. A
 *   `Last-Modified` is pinned for response-to-response consistency only; it is
 *   never sent as an If-Range date because HTTP dates are weak validators by
 *   default (RFC 9110 §8.8.2.2). Weak `ETag`s are ignored for both purposes.
 *   The pinned validator is re-checked on later 206 responses whenever the
 *   same validator header is present; a changed value fails.
 * - Without a strong `ETag`, representation identity cannot be proven. Only the
 *   range/total/body checks apply; a later response that omits the validator
 *   cannot be verified and relies on server correctness.
 * - When a total is known, the download only completes if the written byte
 *   count and the destination length equal that total.
 *
 * Each chunk is appended as it is read. An oversized buffer is rejected before
 * it is written; a short body may already have appended a prefix when the
 * mismatch is detected at end-of-stream, but that chunk and download fail and
 * the prefix is never treated as a completed file.
 *
 * A response that violates these rules fails with [RangeInvalidException]
 * (non-retryable) instead of silently producing a corrupt file.
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
        var representationValidator: RepresentationValidator? = null

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
                    val requestBuilder = Request.Builder()
                        .url(url)
                        .header("User-Agent", DEFAULT_USER_AGENT)
                        .header("Accept-Encoding", "identity")
                        .header("Range", "bytes=$chunkStart-$chunkEnd")
                    // Pin the representation across chunks with a strong ETag.
                    // Last-Modified is never sent as If-Range: HTTP dates are
                    // weak validators by default (RFC 9110 §8.8.2.2), and a
                    // compliant server may answer 200, which cannot be appended.
                    if (chunkStart > 0L) {
                        representationValidator
                            ?.takeIf { it.type.ifRangeEligible }
                            ?.let { requestBuilder.header("If-Range", it.value) }
                    }
                    val call = streamingClient.newCall(requestBuilder.build())
                    // Registered so the worker can abort the blocked read the instant
                    // the network drops instead of waiting out the read timeout.
                    canceller?.register(call)

                    try {
                        call.execute().use { response ->
                        if (response.code == 416) {
                            val knownTotal = totalBytes
                            if (knownTotal == null) {
                                // Unknown size: only a matching unsatisfied-range
                                // total proves that everything up to chunkStart is
                                // all the server has. Without it a malformed or
                                // truncated response could look like EOF.
                                val unsatisfiedTotal = parseUnsatisfiedRangeTotal(
                                    response.header("Content-Range")
                                ) ?: throw RangeNotSatisfiableException(
                                    "HTTP error 416: missing Content-Range (bytes */total) at byte $chunkStart; cannot confirm EOF"
                                )
                                if (unsatisfiedTotal != chunkStart) {
                                    throw RangeNotSatisfiableException(
                                        "HTTP error 416: Content-Range total=$unsatisfiedTotal does not match downloaded=$chunkStart"
                                    )
                                }
                                Logger.d(tag, "HTTP 416 at byte $chunkStart with total=$unsatisfiedTotal. Marking complete.")
                                chunkSuccess = true
                                totalBytes = unsatisfiedTotal
                                return@use
                            }
                            // totalBytes was already resolved but the remaining
                            // range is unsatisfiable: the file is truncated /
                            // content changed underneath us. Retrying is pointless
                            // and completing would silently persist a truncated
                            // file — fail the download.
                            throw RangeNotSatisfiableException(
                                "HTTP error 416: Range Not Satisfiable at byte $chunkStart of resolved total=$knownTotal"
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
                            val reason = if (representationValidator?.type?.ifRangeEligible == true) {
                                "server ignored If-Range (representation may have changed)"
                            } else {
                                "server ignored the Range request"
                            }
                            Logger.w(tag, "Server returned 200 OK for ranged request at byte $chunkStart. Aborting: $reason")
                            throw RangeNotSupportedException(
                                "HTTP error 200: server ignored the Range request at byte $chunkStart ($reason)"
                            )
                        }

                        if (response.code != 206) {
                            throw httpStatusException(response)
                        }

                        val body = response.body ?: throw IOException("Empty response body for chunk $chunkStart-$chunkEnd")

                        // Never append a body that does not match its own
                        // Content-Range: a wrong start/end, a short/long body, or
                        // a changed total silently corrupts the file.
                        val contentRange = parseContentRange(response.header("Content-Range"))
                            ?: throw RangeInvalidException(
                                "Missing/invalid Content-Range for chunk $chunkStart-$chunkEnd"
                            )
                        if (contentRange.start != chunkStart) {
                            throw RangeInvalidException(
                                "Content-Range starts at ${contentRange.start}, expected $chunkStart"
                            )
                        }
                        if (contentRange.end > chunkEnd) {
                            throw RangeInvalidException(
                                "Content-Range ends at ${contentRange.end}, past requested $chunkEnd"
                            )
                        }
                        val claimedBytes = contentRange.end - contentRange.start + 1
                        contentRange.total?.let { responseTotal ->
                            val knownTotal = totalBytes
                            if (knownTotal == null) {
                                totalBytes = responseTotal
                                Logger.d(tag, "Resolved totalBytes=$responseTotal from Content-Range")
                            } else if (knownTotal != responseTotal) {
                                // The object changed size underneath us; completing
                                // would produce a truncated or corrupt file.
                                throw RangeInvalidException(
                                    "Content-Range total changed from $knownTotal to $responseTotal at byte $chunkStart"
                                )
                            }
                        }

                        // Pin the first validator the server supplies. Only a
                        // strong ETag is eligible for If-Range. Last-Modified is
                        // comparison-only because HTTP dates are weak validators
                        // by default (RFC 9110 §8.8.2.2); weak ETags are ignored.
                        // A later 206 is checked against the pinned validator so
                        // a server that ignored If-Range cannot splice in a
                        // different representation of the same length.
                        val pinnedValidator = representationValidator
                        if (pinnedValidator == null) {
                            val strongEtag = response.header(ValidatorType.ETAG.headerName)
                                ?.takeUnless { it.startsWith("W/", ignoreCase = true) }
                            val lastModified = response.header(ValidatorType.LAST_MODIFIED.headerName)
                            representationValidator = when {
                                strongEtag != null ->
                                    RepresentationValidator(ValidatorType.ETAG, strongEtag)
                                lastModified != null ->
                                    RepresentationValidator(ValidatorType.LAST_MODIFIED, lastModified)
                                else -> null
                            }
                        } else {
                            val currentValue = response.header(pinnedValidator.type.headerName)
                            if (currentValue != null && currentValue != pinnedValidator.value) {
                                throw RangeInvalidException(
                                    "Representation ${pinnedValidator.type.headerName} changed " +
                                        "from ${pinnedValidator.value} to $currentValue at byte $chunkStart"
                                )
                            }
                        }

                        val declaredBytes = body.contentLength()
                        if (declaredBytes >= 0L && declaredBytes != claimedBytes) {
                            throw RangeInvalidException(
                                "Chunk body Content-Length $declaredBytes does not match Content-Range claim $claimedBytes"
                            )
                        }
                        var chunkBytesRead = 0L

                        FileOutputStream(destination, true).use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                var bytesRead: Int
                                var lastProgressReport = System.currentTimeMillis()

                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    if (isStopped()) throw CancellationException("Download was stopped")
                                    if (chunkBytesRead + bytesRead > claimedBytes) {
                                        throw RangeInvalidException(
                                            "Chunk body exceeded Content-Range claim of $claimedBytes bytes"
                                        )
                                    }
                                    output.write(buffer, 0, bytesRead)
                                    chunkBytesRead += bytesRead
                                    downloadedBytes += bytesRead

                                    // 500 ms is enough for smooth UI without a Room
                                    // write + notification read twice per second.
                                    val now = System.currentTimeMillis()
                                    if (now - lastProgressReport >= 500) {
                                        onProgress(downloadedBytes, totalBytes)
                                        lastProgressReport = now
                                    }
                                }
                                onProgress(downloadedBytes, totalBytes)
                            }
                        }

                        if (chunkBytesRead != claimedBytes) {
                            throw RangeInvalidException(
                                "Chunk body was $chunkBytesRead bytes but Content-Range claims $claimedBytes"
                            )
                        }

                            chunkSuccess = true
                        }
                    } finally {
                        // Completed calls must not accumulate for the whole job.
                        canceller?.unregister(call)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (canceller?.isCancelled == true) {
                        // The watchdog aborted us (network loss): retrying against a
                        // dead network is pointless — fail the download immediately.
                        throw e
                    }
                    // Permanent range/protocol errors and most 4xx responses cannot
                    // be fixed by repeating the same request.
                    if (!e.isRetryable()) throw e
                    lastError = e
                    Logger.w(tag, "Chunk $chunkStart-$chunkEnd failed (attempt $chunkAttempts/$MAX_RETRIES_PER_CHUNK): ${e.message}")
                    if (chunkAttempts < MAX_RETRIES_PER_CHUNK) {
                        val backoffMs = (e as? HttpStatusException)?.retryAfterMs
                            ?: (1000L * (1 shl (chunkAttempts - 1))).coerceAtMost(10000L)
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

        val resolvedTotal = totalBytes
            ?: throw RangeInvalidException("Chunked download finished without a resolvable total")
        if (downloadedBytes != resolvedTotal) {
            throw RangeInvalidException(
                "Downloaded $downloadedBytes bytes but Content-Range total was $resolvedTotal"
            )
        }
        val writtenLength = destination.length()
        if (writtenLength != downloadedBytes) {
            throw RangeInvalidException(
                "Destination length $writtenLength differs from written bytes $downloadedBytes"
            )
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
                // Keep Content-Length meaningful; a gzip-encoding CDN would make
                // the byte count differ from the downloaded file.
                .header("Accept-Encoding", "identity")
                .build()
        )
        canceller?.register(call)

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw httpStatusException(response)
                }

                val body = response.body ?: throw IOException("Empty response body from $url")
                val contentLength = body.contentLength().takeIf { it > 0 }

                body.byteStream().use { input ->
                    FileOutputStream(destination).use { output ->
                        copyStreamWithProgress(input, output, contentLength, isStopped, onProgress)
                    }
                }
            }
        } finally {
            canceller?.unregister(call)
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
            if (now - lastProgressReport >= 500) {
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

    private enum class ValidatorType(
        val headerName: String,
        /** Only strong ETags may be sent as If-Range (RFC 9110 §13.1.5). */
        val ifRangeEligible: Boolean,
    ) {
        ETAG("ETag", ifRangeEligible = true),
        LAST_MODIFIED("Last-Modified", ifRangeEligible = false),
    }

    private data class RepresentationValidator(
        val type: ValidatorType,
        val value: String,
    )

    private data class ContentRange(
        val start: Long,
        val end: Long,
        val total: Long?,
    )

    private val contentRangeRegex = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
    private val unsatisfiedRangeRegex = Regex("""bytes\s+\*/\s*(\d+)""", RegexOption.IGNORE_CASE)

    private fun parseUnsatisfiedRangeTotal(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val match = unsatisfiedRangeRegex.matchEntire(value.trim()) ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it >= 0L }
    }

    private fun parseContentRange(value: String?): ContentRange? {
        if (value.isNullOrBlank()) return null
        val match = contentRangeRegex.matchEntire(value.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        if (start < 0 || end < start) return null
        val total = match.groupValues[3].let { part ->
            if (part == "*") null else part.toLongOrNull()
        }
        if (total != null && end >= total) return null
        return ContentRange(start, end, total)
    }

    private fun httpStatusException(response: Response): HttpStatusException {
        val retryAfterMs = response.header("Retry-After")
            ?.trim()
            ?.toLongOrNull()
            ?.coerceIn(0L, 60L)
            ?.times(1000L)
        return HttpStatusException(
            code = response.code,
            retryAfterMs = retryAfterMs,
            message = "HTTP error ${response.code}: ${response.message}"
        )
    }

    private fun Throwable.isRetryable(): Boolean = when (this) {
        is RangeNotSatisfiableException,
        is RangeNotSupportedException,
        is RangeInvalidException -> false
        is HttpStatusException -> code == 408 || code == 429 || code in 500..599
        is SocketTimeoutException,
        is ConnectException,
        is UnknownHostException,
        is SocketException -> true
        is IOException -> true
        else -> false
    }
}

/** 416 on a range the resolved total says must exist — the file cannot be completed. */
internal class RangeNotSatisfiableException(message: String) : IOException(message)

/** Server returned 200 for a ranged (non-zero-offset) request — Range support is gone. */
internal class RangeNotSupportedException(message: String) : IOException(message)

/** A 206 response did not match the requested range/body — corruption guard. */
internal class RangeInvalidException(message: String) : IOException(message)

/** Non-2xx response with HTTP status/retry-after retained for typed error mapping. */
internal class HttpStatusException(
    val code: Int,
    val retryAfterMs: Long?,
    message: String,
) : IOException(message)
