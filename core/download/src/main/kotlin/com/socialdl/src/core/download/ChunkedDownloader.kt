package com.socialdl.src.core.download

import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.settings.MAX_CONCURRENT_FRAGMENTS
import com.socialdl.src.core.settings.MIN_CONCURRENT_FRAGMENTS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
 * Each chunk is validated and written as it is read. An oversized buffer is
 * rejected before it is written; a short body may already have written a prefix
 * when the mismatch is detected at end-of-stream, but that chunk and download
 * fail and the prefix is never treated as a completed file. Sequential chunks
 * append; concurrent fragments write positionally into a file pre-sized to the
 * resolved total, so a retry overwrites only its own range and never truncates
 * the shared file.
 *
 * Chunk 0 always runs first, sequentially: that is where the 200 fallback, the
 * 416 EOF proof, the resolved total, and validator pinning are established.
 * When [download] is given `maxConcurrentFragments > 1` and a total is resolved,
 * the remaining ranges are fetched by that many workers. Each worker keeps the
 * same validation/retry rules; a fatal or non-retryable failure (or a stop)
 * aborts every sibling in-flight call through a per-download canceller, without
 * mutating the caller's canceller.
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
        /** Concurrent fragment progress ticks are throttled to this interval. */
        private const val PROGRESS_REPORT_INTERVAL_MS = 500L
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }

    /**
     * Downloads a file using chunked range requests if chunkSizeBytes > 0 or for YouTube,
     * otherwise falls back to a continuous single stream.
     *
     * @param maxConcurrentFragments how many remaining chunk ranges may be fetched
     *   in parallel after chunk 0 resolves the total. Defaults to `1` (sequential)
     *   and is clamped into the supported fragment-concurrency bounds.
     */
    suspend fun download(
        url: String,
        destination: File,
        chunkSizeBytes: Long,
        platform: String,
        isStopped: () -> Boolean,
        canceller: DownloadCallCanceller? = null,
        maxConcurrentFragments: Int = 1,
        onProgress: suspend (bytesWritten: Long, totalBytes: Long?) -> Unit
    ) {
        val effectiveChunkSize = when {
            chunkSizeBytes > 0L -> chunkSizeBytes
            platform.equals("YOUTUBE", ignoreCase = true) || url.contains("googlevideo.com") -> DEFAULT_CHUNK_SIZE
            else -> 0L
        }

        if (effectiveChunkSize > 0L) {
            downloadChunked(
                url = url,
                destination = destination,
                chunkSize = effectiveChunkSize,
                isStopped = isStopped,
                canceller = canceller,
                maxConcurrentFragments = maxConcurrentFragments,
                onProgress = onProgress,
            )
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
        maxConcurrentFragments: Int,
        onProgress: suspend (bytesWritten: Long, totalBytes: Long?) -> Unit
    ) {
        if (destination.exists()) {
            destination.delete()
        }
        destination.createNewFile()

        var downloadedBytes = 0L
        var totalBytes: Long? = null
        var representationValidator: RepresentationValidator? = null

        // Chunk 0 runs first: a 200 single-stream fallback, a 416 EOF proof, the
        // first resolved total, and validator pinning must behave exactly like
        // the original sequential loop before any range is fetched in parallel.
        when (
            val first = downloadSequentialChunk(
                url = url,
                destination = destination,
                chunkStart = 0L,
                chunkEnd = chunkSize - 1,
                knownTotal = null,
                pinnedValidator = null,
                isStopped = isStopped,
                canceller = canceller,
                onProgress = onProgress,
            )
        ) {
            ChunkResult.SingleStreamFallback -> return
            is ChunkResult.EndOfFile -> totalBytes = first.totalBytes
            is ChunkResult.Appended -> {
                downloadedBytes = first.downloadedBytes
                totalBytes = first.totalBytes
                representationValidator = first.validator
            }
        }

        val concurrentTotal = totalBytes
        if (concurrentTotal != null && downloadedBytes < concurrentTotal && maxConcurrentFragments > 1) {
            downloadedBytes = downloadRemainingConcurrently(
                url = url,
                destination = destination,
                alreadyDownloaded = downloadedBytes,
                totalBytes = concurrentTotal,
                chunkSize = chunkSize,
                validator = representationValidator,
                maxConcurrentFragments = maxConcurrentFragments,
                isStopped = isStopped,
                canceller = canceller,
                onProgress = onProgress,
            )
        } else {
            // Unknown total (each next boundary depends on the previous response)
            // or concurrency disabled: keep appending one chunk at a time.
            while (true) {
                val resolved = totalBytes
                if (resolved != null && downloadedBytes >= resolved) break
                if (isStopped()) {
                    throw CancellationException("Download was stopped")
                }

                val chunkStart = downloadedBytes
                val chunkEnd = if (resolved != null) {
                    minOf(chunkStart + chunkSize - 1, resolved - 1)
                } else {
                    chunkStart + chunkSize - 1
                }
                when (
                    val result = downloadSequentialChunk(
                        url = url,
                        destination = destination,
                        chunkStart = chunkStart,
                        chunkEnd = chunkEnd,
                        knownTotal = totalBytes,
                        pinnedValidator = representationValidator,
                        isStopped = isStopped,
                        canceller = canceller,
                        onProgress = onProgress,
                    )
                ) {
                    ChunkResult.SingleStreamFallback -> return
                    is ChunkResult.EndOfFile -> totalBytes = result.totalBytes
                    is ChunkResult.Appended -> {
                        downloadedBytes = result.downloadedBytes
                        totalBytes = result.totalBytes
                        representationValidator = result.validator
                    }
                }
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

    /** Outcome of one completed chunk fetch (all retry attempts included). */
    private sealed interface ChunkResult {
        /** The chunk was written; [downloadedBytes] is the new contiguous offset. */
        data class Appended(
            val downloadedBytes: Long,
            val totalBytes: Long?,
            val validator: RepresentationValidator?,
        ) : ChunkResult

        /** A 416 proved the representation ends exactly where the download already is. */
        data class EndOfFile(val totalBytes: Long) : ChunkResult

        /** A 200 answer streamed the whole representation, so nothing else may be requested. */
        data object SingleStreamFallback : ChunkResult
    }

    /**
     * Fetches one chunk with up to [MAX_RETRIES_PER_CHUNK] attempts, appending to
     * [destination] (which must currently end at [chunkStart]). A retry truncates
     * any bytes appended by the failed attempt back to [chunkStart].
     */
    private suspend fun downloadSequentialChunk(
        url: String,
        destination: File,
        chunkStart: Long,
        chunkEnd: Long,
        knownTotal: Long?,
        pinnedValidator: RepresentationValidator?,
        isStopped: () -> Boolean,
        canceller: DownloadCallCanceller?,
        onProgress: suspend (bytesWritten: Long, totalBytes: Long?) -> Unit,
    ): ChunkResult {
        var lastError: Exception? = null
        var attempt = 0
        while (attempt < MAX_RETRIES_PER_CHUNK) {
            attempt++
            if (isStopped()) {
                throw CancellationException("Download was stopped")
            }
            try {
                val call = streamingClient.newCall(
                    buildChunkRequest(url, chunkStart, chunkEnd, pinnedValidator)
                )
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
                                knownTotal = knownTotal,
                                contentRangeHeader = response.header("Content-Range"),
                                chunkStart = chunkStart
                            )
                            Logger.d(tag, "HTTP 416 at byte $chunkStart with total=$confirmedTotal. Marking complete.")
                            return ChunkResult.EndOfFile(confirmedTotal)
                        }

                        if (response.code == 200 && chunkStart == 0L) {
                            Logger.w(tag, "Server returned 200 OK without Range support. Falling back to single stream.")
                            streamFallbackBody(url, destination, response, isStopped, onProgress)
                            // The body was the WHOLE representation; re-entering the
                            // chunk loop would request from byte 0 again (infinite
                            // loop for a zero Content-Length) or fail with
                            // RangeNotSupported even though the transfer was valid.
                            return ChunkResult.SingleStreamFallback
                        }

                        if (response.code == 200) {
                            // Server stopped honoring Range mid-download (or never
                            // honored it for this URL). Retrying the same request
                            // yields the same 200 — fail fast instead of burning
                            // backoff cycles and full-body connection discards.
                            throw rangeNotSupportedException(chunkStart, pinnedValidator)
                        }

                        if (response.code != 206) {
                            throw httpStatusException(response)
                        }

                        // Never append a body that does not match its own
                        // Content-Range: a wrong start/end, a short/long body, or
                        // a changed representation silently corrupts the file.
                        val validated = validateChunkResponse(
                            response = response,
                            chunkStart = chunkStart,
                            chunkEnd = chunkEnd,
                            knownTotal = knownTotal,
                            pinnedValidator = pinnedValidator,
                        )
                        val chunkBytesRead = validated.body.byteStream().use { input ->
                            FileOutputStream(destination, true).use { output ->
                                copyStreamWithProgress(
                                    input = input,
                                    output = output,
                                    totalBytes = validated.totalBytes,
                                    isStopped = isStopped,
                                    onProgress = onProgress,
                                    offsetBytes = chunkStart,
                                    maxBytes = validated.claimedBytes,
                                )
                            }
                        }

                        if (chunkBytesRead != validated.claimedBytes) {
                            throw RangeInvalidException(
                                "Chunk body was $chunkBytesRead bytes but Content-Range claims ${validated.claimedBytes}"
                            )
                        }
                        return ChunkResult.Appended(
                            downloadedBytes = chunkStart + chunkBytesRead,
                            totalBytes = validated.totalBytes,
                            validator = validated.validator,
                        )
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
                Logger.w(
                    tag,
                    "Chunk $chunkStart-$chunkEnd failed (attempt $attempt/$MAX_RETRIES_PER_CHUNK): ${e.message}"
                )
                if (attempt < MAX_RETRIES_PER_CHUNK) {
                    delay(backoffMsFor(e, attempt))
                    truncateFile(destination, chunkStart)
                }
            }
        }
        throw IOException(
            "Failed to download chunk $chunkStart-$chunkEnd after $MAX_RETRIES_PER_CHUNK attempts",
            lastError
        )
    }

    /**
     * Streams every remaining range into a pre-sized [destination] with up to
     * [maxConcurrentFragments] workers. Each worker owns complete chunks and
     * writes them positionally, so retries overwrite only their own range.
     *
     * A sibling [DownloadCallCanceller] is registered alongside the caller's for
     * every call: a fatal/non-retryable error or a stop cancels it, aborting all
     * in-flight sibling calls immediately without mutating the caller's state.
     *
     * @return the total bytes written once every range completed.
     */
    private suspend fun downloadRemainingConcurrently(
        url: String,
        destination: File,
        alreadyDownloaded: Long,
        totalBytes: Long,
        chunkSize: Long,
        validator: RepresentationValidator?,
        maxConcurrentFragments: Int,
        isStopped: () -> Boolean,
        canceller: DownloadCallCanceller?,
        onProgress: suspend (bytesWritten: Long, totalBytes: Long?) -> Unit,
    ): Long {
        val ranges = buildList {
            var start = alreadyDownloaded
            while (start < totalBytes) {
                val end = minOf(start + chunkSize - 1, totalBytes - 1)
                add(start..end)
                start = end + 1
            }
        }
        if (ranges.isEmpty()) return alreadyDownloaded

        val workerCount = minOf(
            maxConcurrentFragments.coerceIn(MIN_CONCURRENT_FRAGMENTS, MAX_CONCURRENT_FRAGMENTS),
            ranges.size,
        )
        val writtenBytes = AtomicLong(alreadyDownloaded)
        val lastProgressAt = AtomicLong(0L)
        val nextRange = AtomicInteger(0)
        val siblingCanceller = DownloadCallCanceller()

        Logger.d(
            tag,
            "Downloading ${ranges.size} remaining chunks with $workerCount concurrent workers"
        )

        try {
            RandomAccessFile(destination, "rw").use { raf ->
                raf.setLength(totalBytes)
                val channel = raf.channel
                coroutineScope {
                    // Bridges coroutine cancellation (user cancel / system stop)
                    // to the blocking OkHttp reads: cancelling a coroutine alone
                    // cannot interrupt a socket read, but cancelling its call can.
                    val cancellationBridge = launch {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            continuation.invokeOnCancellation { siblingCanceller.cancel() }
                        }
                    }
                    try {
                        coroutineScope {
                            repeat(workerCount) {
                                launch(Dispatchers.IO) {
                                    try {
                                        while (true) {
                                            if (isStopped()) {
                                                throw CancellationException("Download was stopped")
                                            }
                                            if (siblingCanceller.isCancelled || canceller?.isCancelled == true) {
                                                throw CancellationException("Download was cancelled")
                                            }
                                            val index = nextRange.getAndIncrement()
                                            if (index >= ranges.size) break
                                            val range = ranges[index]
                                            downloadChunkToChannel(
                                                url = url,
                                                channel = channel,
                                                chunkStart = range.first,
                                                chunkEnd = range.last,
                                                totalBytes = totalBytes,
                                                validator = validator,
                                                writtenBytes = writtenBytes,
                                                lastProgressAt = lastProgressAt,
                                                isStopped = isStopped,
                                                canceller = siblingCanceller,
                                                callerCanceller = canceller,
                                                onProgress = onProgress,
                                            )
                                        }
                                    } catch (e: Throwable) {
                                        siblingCanceller.cancel()
                                        throw e
                                    }
                                }
                            }
                        }
                    } finally {
                        cancellationBridge.cancel()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A stop that raced a sibling's aborted call must still surface as
            // cancellation rather than as the collateral IO failure.
            if (isStopped()) throw CancellationException("Download was stopped")
            throw e
        }

        if (isStopped()) throw CancellationException("Download was stopped")
        val written = writtenBytes.get()
        if (written != totalBytes) {
            throw RangeInvalidException(
                "Concurrent download wrote $written bytes but Content-Range total was $totalBytes"
            )
        }
        onProgress(totalBytes, totalBytes)
        return totalBytes
    }

    /**
     * Fetches the assigned `[chunkStart]..[chunkEnd]` byte range into [channel].
     * If a server returns a shorter valid 206 slice (`end < chunkEnd`), loops over
     * the remaining sub-range so no hole is left in the pre-sized file.
     */
    private suspend fun downloadChunkToChannel(
        url: String,
        channel: FileChannel,
        chunkStart: Long,
        chunkEnd: Long,
        totalBytes: Long,
        validator: RepresentationValidator?,
        writtenBytes: AtomicLong,
        lastProgressAt: AtomicLong,
        isStopped: () -> Boolean,
        canceller: DownloadCallCanceller,
        callerCanceller: DownloadCallCanceller?,
        onProgress: suspend (bytesWritten: Long, totalBytes: Long?) -> Unit,
    ) {
        val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
        var currentStart = chunkStart
        while (currentStart <= chunkEnd) {
            currentStart = downloadSubRangeToChannel(
                url = url,
                channel = channel,
                buffer = buffer,
                rangeStart = currentStart,
                rangeEnd = chunkEnd,
                totalBytes = totalBytes,
                validator = validator,
                writtenBytes = writtenBytes,
                lastProgressAt = lastProgressAt,
                isStopped = isStopped,
                canceller = canceller,
                callerCanceller = callerCanceller,
                onProgress = onProgress,
            )
        }
    }

    /**
     * One sub-range inside a concurrent chunk: up to [MAX_RETRIES_PER_CHUNK] attempts,
     * each writing at its absolute [rangeStart] so a retry overwrites only its own range.
     * A failed attempt's partial bytes are rolled back out of [writtenBytes] before the
     * next attempt so progress never overshoots the total. Returns the next offset after
     * the validated bytes written.
     */
    private suspend fun downloadSubRangeToChannel(
        url: String,
        channel: FileChannel,
        buffer: ByteBuffer,
        rangeStart: Long,
        rangeEnd: Long,
        totalBytes: Long,
        validator: RepresentationValidator?,
        writtenBytes: AtomicLong,
        lastProgressAt: AtomicLong,
        isStopped: () -> Boolean,
        canceller: DownloadCallCanceller,
        callerCanceller: DownloadCallCanceller?,
        onProgress: suspend (bytesWritten: Long, totalBytes: Long?) -> Unit,
    ): Long {
        var lastError: Exception? = null
        var attempt = 0
        while (attempt < MAX_RETRIES_PER_CHUNK) {
            attempt++
            if (isStopped()) {
                throw CancellationException("Download was stopped")
            }
            if (canceller.isCancelled || callerCanceller?.isCancelled == true) {
                throw CancellationException("Download was cancelled")
            }

            var attemptBytes = 0L
            try {
                val call = streamingClient.newCall(
                    buildChunkRequest(url, rangeStart, rangeEnd, validator)
                )
                // Registered on the sibling too: any worker's fatal failure or
                // cancellation aborts every in-flight call of this download.
                canceller.register(call)
                callerCanceller?.register(call)
                try {
                    call.execute().use { response ->
                        if (response.code == 200) {
                            throw rangeNotSupportedException(rangeStart, validator)
                        }
                        if (response.code == 416) {
                            // Every concurrent range comes from a resolved total,
                            // so a 416 here means the representation shrank or
                            // changed. confirmEndOfFile raises the typed failure.
                            confirmEndOfFile(totalBytes, response.header("Content-Range"), rangeStart)
                        }
                        if (response.code != 206) {
                            throw httpStatusException(response)
                        }

                        val validated = validateChunkResponse(
                            response = response,
                            chunkStart = rangeStart,
                            chunkEnd = rangeEnd,
                            knownTotal = totalBytes,
                            pinnedValidator = validator,
                        )

                        var position = rangeStart
                        var bytesRead = 0L
                        validated.body.source().use { source ->
                            while (true) {
                                buffer.clear()
                                val read = source.read(buffer)
                                if (read == -1) break
                                if (isStopped()) {
                                    throw CancellationException("Download was stopped")
                                }
                                if (bytesRead + read > validated.claimedBytes) {
                                    throw RangeInvalidException(
                                        "Chunk body exceeded Content-Range claim of ${validated.claimedBytes} bytes"
                                    )
                                }
                                buffer.flip()
                                try {
                                    writeFullyAt(channel, buffer, read, position)
                                } catch (e: IOException) {
                                    // Account bytes the channel accepted before the
                                    // failure so this attempt's rollback stays exact.
                                    val accepted = buffer.position().toLong()
                                    writtenBytes.addAndGet(accepted)
                                    attemptBytes += accepted
                                    throw e
                                }
                                position += read
                                bytesRead += read
                                writtenBytes.addAndGet(read.toLong())
                                attemptBytes += read
                                reportProgressThrottled(
                                    writtenBytes = writtenBytes.get(),
                                    totalBytes = totalBytes,
                                    lastProgressAt = lastProgressAt,
                                    onProgress = onProgress,
                                )
                            }
                        }

                        if (bytesRead != validated.claimedBytes) {
                            throw RangeInvalidException(
                                "Chunk body was $bytesRead bytes but Content-Range claims ${validated.claimedBytes}"
                            )
                        }
                        return rangeStart + bytesRead
                    }
                } finally {
                    callerCanceller?.unregister(call)
                    canceller.unregister(call)
                }
            } catch (e: CancellationException) {
                // A stop/cancellation in one worker aborts every sibling call.
                canceller.cancel()
                throw e
            } catch (e: Exception) {
                if (callerCanceller?.isCancelled == true) {
                    // Watchdog abort (network loss): same semantics as sequential.
                    throw e
                }
                if (canceller.isCancelled) {
                    // A sibling aborted the download. Exit quietly so its typed
                    // failure is what the caller observes, not this collateral IO.
                    throw CancellationException("Download was cancelled")
                }
                writtenBytes.addAndGet(-attemptBytes)
                if (!e.isRetryableTransferError()) {
                    // A broken range/representation invalidates the whole file:
                    // abort every sibling call instead of only this chunk.
                    canceller.cancel()
                    throw e
                }
                lastError = e
                Logger.w(
                    tag,
                    "Chunk $rangeStart-$rangeEnd failed (attempt $attempt/$MAX_RETRIES_PER_CHUNK): ${e.message}"
                )
                if (attempt < MAX_RETRIES_PER_CHUNK) {
                    delay(backoffMsFor(e, attempt))
                }
            }
        }
        canceller.cancel()
        throw IOException(
            "Failed to download chunk $rangeStart-$rangeEnd after $MAX_RETRIES_PER_CHUNK attempts",
            lastError
        )
    }

    private suspend fun downloadSingleStream(
        url: String,
        destination: File,
        isStopped: () -> Boolean,
        canceller: DownloadCallCanceller?,
        onProgress: suspend (bytesWritten: Long, totalBytes: Long?) -> Unit
    ) {
        val call = streamingClient.newCall(baseRequestBuilder(url).build())
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

    /** Streams the whole representation of a 200 answer to the first ranged request. */
    private suspend fun streamFallbackBody(
        url: String,
        destination: File,
        response: Response,
        isStopped: () -> Boolean,
        onProgress: suspend (bytesWritten: Long, totalBytes: Long?) -> Unit,
    ) {
        val body = response.body ?: throw IOException("Empty response body from $url")
        // Null when the server sends a chunked body (-1) or an explicit zero
        // length: only the bytes actually written can prove completion then.
        val declaredTotal = body.contentLength().takeIf { it > 0 }
        body.byteStream().use { input ->
            FileOutputStream(destination, false).use { output ->
                copyStreamWithProgress(input, output, declaredTotal, isStopped, onProgress)
            }
        }
        // A declared length must match what was written before this fallback may
        // count as complete.
        val written = destination.length()
        if (declaredTotal != null && written != declaredTotal) {
            throw RangeInvalidException(
                "Fallback body was $written bytes but Content-Length was $declaredTotal"
            )
        }
    }

    private fun rangeNotSupportedException(
        chunkStart: Long,
        validator: RepresentationValidator?,
    ): RangeNotSupportedException {
        val reason = if (validator?.type?.ifRangeEligible == true) {
            "server ignored If-Range (representation may have changed)"
        } else {
            "server ignored the Range request"
        }
        Logger.w(tag, "Server returned 200 OK for ranged request at byte $chunkStart. Aborting: $reason")
        return RangeNotSupportedException(
            "HTTP error 200: server ignored the Range request at byte $chunkStart ($reason)"
        )
    }

    /** Request headers shared by every download path (range and single-stream). */
    private fun baseRequestBuilder(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_USER_AGENT)
            // Keep Content-Length meaningful; a gzip-encoding CDN would make the
            // byte count differ from the downloaded file.
            .header("Accept-Encoding", "identity")

    /** Ranged request for one chunk, pinning the representation with a strong ETag. */
    private fun buildChunkRequest(
        url: String,
        chunkStart: Long,
        chunkEnd: Long,
        validator: RepresentationValidator?,
    ): Request {
        val builder = baseRequestBuilder(url)
            .header("Range", "bytes=$chunkStart-$chunkEnd")
        // Last-Modified is never sent as If-Range: HTTP dates are weak validators
        // by default (RFC 9110 §8.8.2.2), and a compliant server may answer 200,
        // which cannot be appended.
        if (chunkStart > 0L) {
            validator?.takeIf { it.type.ifRangeEligible }?.let { builder.header("If-Range", it.value) }
        }
        return builder.build()
    }

    /** A 206 response whose headers/body have been validated against the request. */
    private data class ValidatedChunk(
        val body: ResponseBody,
        val claimedBytes: Long,
        val totalBytes: Long?,
        val validator: RepresentationValidator?,
    )

    /**
     * Validates a 206 against its own `Content-Range`, the pinned total, the
     * pinned representation validator, and its declared `Content-Length`.
     */
    private fun validateChunkResponse(
        response: Response,
        chunkStart: Long,
        chunkEnd: Long,
        knownTotal: Long?,
        pinnedValidator: RepresentationValidator?,
    ): ValidatedChunk {
        val body = response.body
            ?: throw IOException("Empty response body for chunk $chunkStart-$chunkEnd")
        val contentRange = parseContentRange(response.header("Content-Range"))
            ?: throw RangeInvalidException(
                "Missing/invalid Content-Range for chunk $chunkStart-$chunkEnd"
            )
        validateChunkRange(contentRange, chunkStart, chunkEnd)
        val claimedBytes = contentRange.end - contentRange.start + 1
        val totalBeforeMerge = knownTotal
        val totalBytes = mergeTotal(knownTotal, contentRange, chunkStart)
        if (totalBeforeMerge == null && totalBytes != null) {
            Logger.d(tag, "Resolved totalBytes=$totalBytes from Content-Range")
        }
        val validator = resolveValidator(response, pinnedValidator, chunkStart)
        val declaredBytes = body.contentLength()
        if (declaredBytes >= 0L && declaredBytes != claimedBytes) {
            throw RangeInvalidException(
                "Chunk body Content-Length $declaredBytes does not match Content-Range claim $claimedBytes"
            )
        }
        return ValidatedChunk(body, claimedBytes, totalBytes, validator)
    }

    /**
     * Pins the first validator the server supplies. Only a strong ETag is eligible
     * for If-Range; Last-Modified is comparison-only and weak ETags are ignored.
     * A later 206 is checked against the pinned validator so a server that ignored
     * If-Range cannot splice in a different representation of the same length.
     */
    private fun resolveValidator(
        response: Response,
        pinned: RepresentationValidator?,
        chunkStart: Long,
    ): RepresentationValidator? {
        if (pinned == null) {
            val strongEtag = response.header(ValidatorType.ETAG.headerName)
                ?.takeUnless { it.startsWith("W/", ignoreCase = true) }
            val lastModified = response.header(ValidatorType.LAST_MODIFIED.headerName)
            return when {
                strongEtag != null -> RepresentationValidator(ValidatorType.ETAG, strongEtag)
                lastModified != null -> RepresentationValidator(ValidatorType.LAST_MODIFIED, lastModified)
                else -> null
            }
        }
        val currentValue = response.header(pinned.type.headerName)
        if (currentValue != null && currentValue != pinned.value) {
            throw RangeInvalidException(
                "Representation ${pinned.type.headerName} changed " +
                    "from ${pinned.value} to $currentValue at byte $chunkStart"
            )
        }
        return pinned
    }

    /**
     * Writes the flipped [buffer]'s first [length] bytes at the absolute
     * [position], looping while each channel write accepts only a prefix.
     * The buffer position advances by exactly the bytes written, so a failed
     * write can report how much it accepted.
     */
    private fun writeFullyAt(channel: FileChannel, buffer: ByteBuffer, length: Int, position: Long) {
        buffer.limit(length)
        var writePosition = position
        while (buffer.hasRemaining()) {
            writePosition += channel.write(buffer, writePosition)
        }
    }

    private suspend fun reportProgressThrottled(
        writtenBytes: Long,
        totalBytes: Long,
        lastProgressAt: AtomicLong,
        onProgress: suspend (bytesWritten: Long, totalBytes: Long?) -> Unit,
    ) {
        val now = System.currentTimeMillis()
        val previous = lastProgressAt.get()
        if (now - previous >= PROGRESS_REPORT_INTERVAL_MS && lastProgressAt.compareAndSet(previous, now)) {
            onProgress(writtenBytes, totalBytes)
        }
    }

    private fun backoffMsFor(error: Exception, attempt: Int): Long =
        (error as? HttpStatusException)?.retryAfterMs
            ?: (1000L * (1 shl (attempt - 1))).coerceAtMost(10_000L)

    private fun truncateFile(file: File, targetSize: Long) {
        if (file.exists() && file.length() > targetSize) {
            RandomAccessFile(file, "rw").use { raf ->
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
