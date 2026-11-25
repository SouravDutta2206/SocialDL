package com.socialdl.src.core.download

import kotlinx.coroutines.CancellationException
import java.io.InputStream
import java.io.OutputStream

/** A parsed `Content-Range: bytes start-end/total` header. `total` is null for `*`. */
internal data class ContentRange(val start: Long, val end: Long, val total: Long?)

private val contentRangeRegex = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
private val unsatisfiedRangeRegex = Regex("""bytes\s+\*/\s*(\d+)""", RegexOption.IGNORE_CASE)

internal fun parseContentRange(value: String?): ContentRange? {
    if (value.isNullOrBlank()) return null
    val match = contentRangeRegex.matchEntire(value.trim()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    if (start < 0 || end < start) return null
    val total = match.groupValues[3].let { part -> if (part == "*") null else part.toLongOrNull() }
    if (total != null && end >= total) return null
    return ContentRange(start, end, total)
}

internal fun parseUnsatisfiedRangeTotal(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    val match = unsatisfiedRangeRegex.matchEntire(value.trim()) ?: return null
    return match.groupValues[1].toLongOrNull()?.takeIf { it >= 0L }
}

/** Checks a 206 response's own claim against the range we asked for. */
internal fun validateChunkRange(claimed: ContentRange, requestedStart: Long, requestedEnd: Long) {
    if (claimed.start != requestedStart) {
        throw RangeInvalidException("Content-Range starts at ${claimed.start}, expected $requestedStart")
    }
    if (claimed.end > requestedEnd) {
        throw RangeInvalidException("Content-Range ends at ${claimed.end}, past requested $requestedEnd")
    }
}

/** Returns the total to keep after this response. Fails if the object changed size. */
internal fun mergeTotal(known: Long?, claimed: ContentRange, requestedStart: Long): Long? {
    val responseTotal = claimed.total ?: return known
    if (known == null) return responseTotal
    if (known != responseTotal) {
        throw RangeInvalidException("Content-Range total changed from $known to $responseTotal at byte $requestedStart")
    }
    return known
}

/** Proof of end-of-file for a 416. Returns the confirmed total or throws. */
internal fun confirmEndOfFile(knownTotal: Long?, contentRangeHeader: String?, chunkStart: Long): Long {
    if (knownTotal != null) {
        throw RangeNotSatisfiableException(
            "HTTP error 416: Range Not Satisfiable at byte $chunkStart of resolved total=$knownTotal"
        )
    }
    val unsatisfiedTotal = parseUnsatisfiedRangeTotal(contentRangeHeader)
        ?: throw RangeNotSatisfiableException(
            "HTTP error 416: missing Content-Range (bytes */total) at byte $chunkStart; cannot confirm EOF"
        )
    if (unsatisfiedTotal != chunkStart) {
        throw RangeNotSatisfiableException(
            "HTTP error 416: Content-Range total=$unsatisfiedTotal does not match downloaded=$chunkStart"
        )
    }
    return unsatisfiedTotal
}

/**
 * Copies one stream body into [output]. When [maxBytes] is set (a 206 chunk's claimed
 * range length), bytes beyond it are rejected before they are written. Progress is
 * reported as `offsetBytes + bytesWrittenSoFar`, throttled to 500 ms, with a final report.
 * Returns the number of bytes written.
 */
internal suspend fun copyStreamWithProgress(
    input: InputStream,
    output: OutputStream,
    totalBytes: Long?,
    isStopped: () -> Boolean,
    onProgress: suspend (bytesWritten: Long, totalBytes: Long?) -> Unit,
    offsetBytes: Long = 0L,
    maxBytes: Long? = null,
): Long {
    val buffer = ByteArray(64 * 1024)
    var bytesReadTotal = 0L
    var lastProgressReport = System.currentTimeMillis()
    while (true) {
        val bytesRead = input.read(buffer)
        if (bytesRead == -1) break
        if (isStopped()) throw CancellationException("Download was stopped")
        if (maxBytes != null && bytesReadTotal + bytesRead > maxBytes) {
            throw RangeInvalidException("Chunk body exceeded Content-Range claim of $maxBytes bytes")
        }
        output.write(buffer, 0, bytesRead)
        bytesReadTotal += bytesRead
        val now = System.currentTimeMillis()
        if (now - lastProgressReport >= 500) {
            onProgress(offsetBytes + bytesReadTotal, totalBytes)
            lastProgressReport = now
        }
    }
    onProgress(offsetBytes + bytesReadTotal, totalBytes)
    return bytesReadTotal
}
