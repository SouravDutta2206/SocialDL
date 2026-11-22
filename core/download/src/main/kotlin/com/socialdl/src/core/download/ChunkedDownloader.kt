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
 * - A 200 response to the first ranged request means the server declined Range:
 *   that body is the whole representation, so it is streamed once and the
 *   download returns instead of re-entering the chunk loop (a chunked or
 *   zero-length body never yields a total to loop against).
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
        /** Bounded body peek for a non-2xx response — diagnostics only. */
        private const val ERROR_BODY_PEEK_BYTES = 512L
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
                            // Unknown size: only a matching unsatisfied-range total
                            // proves everything up to chunkStart is the whole file.
                            // A resolved total that is now unsatisfiable means the
                            // file is truncated or changed underneath us; retrying
                            // is pointless and completing would persist corruption.
                            val confirmedTotal = confirmEndOfFile(
                                knownTotal = totalBytes,
                                contentRangeHeader = response.header("Content-Range"),
                                chunkStart = chunkStart
                            )
                            Logger.d(tag, "HTTP 416 at byte $chunkStart with total=$confirmedTotal. Marking complete.")
                            chunkSuccess = true
                            totalBytes = confirmedTotal
                            return@use
                        }

                        if (response.code == 200 && chunkStart == 0L) {
                            Logger.w(tag, "Server returned 200 OK without Range support. Falling back to single stream.")
                            val body = response.body ?: throw IOException("Empty response body from $url")
                            // Null when the server sends a chunked body (-1) or an
                            // explicit zero length: only the bytes actually written
                            // can prove completion in those cases.
                            val cl = body.contentLength().takeIf { it > 0 }
                            totalBytes = cl
                            body.byteStream().use { input ->
                                FileOutputStream(destination, false).use { output ->
                                    copyStreamWithProgress(input, output, cl, isStopped, onProgress)
                                }
                            }
                            chunkSuccess = true
                            downloadedBytes = destination.length()
                            // A declared length must match what was written before
                            // this fallback may count as complete.
                            if (cl != null && downloadedBytes != cl) {
                                throw RangeInvalidException(
                                    "Fallback body was $downloadedBytes bytes but Content-Length was $cl"
                                )
                            }
                            // Bug #3: leave downloadChunked entirely. This body was
                            // the WHOLE representation; re-entering the chunk loop
                            // would either request from byte 0 again (infinite loop
                            // for a zero Content-Length) or fail with
                            // RangeNotSupported / "no resolvable total" even though
                            // the transfer was valid. `return` is non-local out of
                            // the inline `use`, so the response still closes and the
                            // outer finally still unregisters the call.
                            return
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
                        validateChunkRange(contentRange, chunkStart, chunkEnd)
                        val claimedBytes = contentRange.end - contentRange.start + 1
                        val totalBeforeMerge = totalBytes
                        totalBytes = mergeTotal(totalBytes, contentRange, chunkStart)
                        if (totalBeforeMerge == null && totalBytes != null) {
                            Logger.d(tag, "Resolved totalBytes=$totalBytes from Content-Range")
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
                        val chunkBytesRead = FileOutputStream(destination, true).use { output ->
                            body.byteStream().use { input ->
                                copyChunkBody(
                                    input = input,
                                    output = output,
                                    chunkStart = chunkStart,
                                    claimedBytes = claimedBytes,
                                    totalBytes = totalBytes,
                                    isStopped = isStopped,
                                    onProgress = onProgress
                                )
                            }
                        }

                        if (chunkBytesRead != claimedBytes) {
                            throw RangeInvalidException(
                                "Chunk body was $chunkBytesRead bytes but Content-Range claims $claimedBytes"
                            )
                        }
                        downloadedBytes = chunkStart + chunkBytesRead

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
                    if (!e.isRetryableTransferError()) throw e
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

    private fun httpStatusException(response: Response): HttpStatusException {
        val retryAfterMs = response.header("Retry-After")
            ?.trim()
            ?.toLongOrNull()
            ?.coerceIn(0L, 60L)
            ?.times(1000L)
        // Never log the signed URL: the media host, the `c` player-client parameter
        // yt-dlp embedded, and the first bytes of the refusal body are what separate
        // "this signature is no longer accepted" from "this stream is gated".
        Logger.w(
            tag,
            "HTTP ${response.code} for ${response.request.url.host} " +
                "(client=${response.request.url.queryParameter("c") ?: "?"}, " +
                "contentType=${response.header("Content-Type") ?: "?"}) " +
                "body=${errorBodySnippet(response)}"
        )
        return HttpStatusException(
            code = response.code,
            retryAfterMs = retryAfterMs,
            message = "HTTP error ${response.code}: ${response.message}"
        )
    }

    /** First bytes of a refusal body, collapsed to one line; never the request URL. */
    private fun errorBodySnippet(response: Response): String =
        runCatching {
            response.peekBody(ERROR_BODY_PEEK_BYTES)
                .string()
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(180)
                .ifEmpty { "<empty>" }
        }.getOrDefault("<unreadable>")

}
