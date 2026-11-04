package com.socialdl.src.core.download

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 416 on a range that the resolved total says must exist. The file cannot be completed. */
internal class RangeNotSatisfiableException(message: String) : IOException(message)

/** The server returned 200 for a ranged (non-zero-offset) request, so Range support is gone. */
internal class RangeNotSupportedException(message: String) : IOException(message)

/** A 206 response did not match the requested range or body. Corruption guard. */
internal class RangeInvalidException(message: String) : IOException(message)

/** Non-2xx response, with the HTTP status and Retry-After kept for typed error mapping. */
internal class HttpStatusException(
    val code: Int,
    val retryAfterMs: Long?,
    message: String,
) : IOException(message)

/** The container/MIME pair can never be inserted into MediaStore (e.g. audio/webm). */
internal class UnsupportedMediaTypeException(message: String) : IllegalArgumentException(message)

/** True for ENOSPC or "No space left on device". Waiting does not fix it. */
internal fun Throwable.isStorageFull(): Boolean =
    this is IOException && message.orEmpty().let {
        it.contains("ENOSPC", ignoreCase = true) || it.contains("No space left", ignoreCase = true)
    }

/** The single retry rule: can repeating the same request succeed? */
internal fun Throwable.isRetryableTransferError(): Boolean = when (this) {
    is RangeNotSatisfiableException,
    is RangeNotSupportedException,
    is RangeInvalidException -> false
    is HttpStatusException -> code == 408 || code == 429 || code in 500..599
    is SocketTimeoutException,
    is ConnectException,
    is UnknownHostException,
    is SocketException -> true
    is IOException -> !isStorageFull()
    else -> false
}

/**
 * Typed, user-facing error categories for the download pipeline (§9).
 *
 * The UI must render [userMessage] only — raw exception messages stay in
 * diagnostics logs ([com.socialdl.src.core.common.util.Logger]).
 * Exceptions are mapped to a category in one place via [from], used by the
 * worker's catch-all and every Failed emit site.
 */
sealed class DownloadError(val userMessage: String) {

    /** Source URL has no registered extractor. */
    data object NoExtractor : DownloadError("This link isn't supported yet")

    /** Extractor/JIT stream-resolution error (details in logs only). */
    data object ExtractionFailed : DownloadError("Couldn't fetch media info for this link")

    /** Format resolved but no direct stream URL is available. */
    data object MissingStream : DownloadError("Could not find a downloadable stream for this format")

    /**
     * The extracted item has no usable stream of the requested media kind
     * (audio/video/image). Never resolved by substituting another kind.
     */
    data object FormatUnavailable : DownloadError(
        "This format isn't available for this media — pick another quality"
    )

    /**
     * The job row's stored format kind is blank or unrecognized. Only corrupt or
     * legacy rows can reach this path; guessing a kind could export the wrong
     * media type, so the worker fails closed.
     */
    data object InvalidFormatKind : DownloadError(
        "This download's format information is invalid — start it again"
    )

    /** Format requires muxing but no audio track could be resolved. */
    data object MissingAudioStream : DownloadError("This format needs merging but no audio track was available")

    /** Transfer-level network failure (timeout, DNS, connection, socket). */
    data object NetworkFailure : DownloadError("Network error — check your connection and try again")

    /** Server rejected the download (HTTP error status). */
    data object ServerError : DownloadError("The server rejected the download")

    /** Permanent 404/410: the media was deleted, made private, or the URL expired. */
    data object NotFound : DownloadError("This media is no longer available")

    /** 401/403: signed-out, region-locked, or a revoked CDN token. */
    data object AccessDenied : DownloadError("The server refused access to this media")

    /** 429 after bridge/downloader retries. */
    data object RateLimited : DownloadError("Too many requests — try again in a few minutes")

    /** ENOSPC or another local write failure. */
    data object StorageFull : DownloadError("Not enough free storage to save this download")

    /** MediaStore/SAF permission missing (e.g. Android 9 storage permission). */
    data object StoragePermission : DownloadError("Storage permission is required to save this download")

    /** FFmpeg video+audio merge failed (ffmpeg log kept in Logger only). */
    data object MuxFailed : DownloadError("Error while merging video and audio")

    /** MediaStore export failure (disk-full, permission, no provider). */
    data object ExportFailed : DownloadError("Failed to save file to system media storage")

    /** The requested container cannot be written to the media library at all. */
    data object UnsupportedFormat : DownloadError("This format can't be saved to your media library")

    /** FFmpeg single-stream conversion failed (copy or encode; log kept in Logger only). */
    data object ConversionFailed : DownloadError("Couldn't convert the file to the selected format")

    /** Reconciled as failed after process death / system stop. */
    data object Interrupted : DownloadError("Download was interrupted")

    /** Catch-all for unexpected errors (full diagnostics in logs only). */
    data object Unknown : DownloadError("Something went wrong while downloading")

    companion object {
        private val HTTP_ERROR_REGEX = Regex("""HTTP error (\d{3})""")
        private const val MAX_CAUSE_DEPTH = 8

        fun from(throwable: Throwable): DownloadError {
            if (throwable is CancellationException) return Interrupted

            // Classify on the whole cause chain. A wrapped failure keeps its typed cause.
            val chain = generateSequence(throwable) { previous ->
                previous.cause?.takeIf { cause -> cause !== previous }
            }.take(MAX_CAUSE_DEPTH).toList()

            chain.filterIsInstance<HttpStatusException>().firstOrNull()?.let { return fromStatus(it.code) }
            if (chain.any { it.isStorageFull() }) return StorageFull

            return when (throwable) {
                is RangeInvalidException,
                is RangeNotSatisfiableException,
                is RangeNotSupportedException -> ServerError

                is UnsupportedMediaTypeException -> UnsupportedFormat

                is SecurityException -> StoragePermission

                is SocketTimeoutException,
                is UnknownHostException,
                is ConnectException,
                is SocketException -> NetworkFailure

                is IOException ->
                    if (HTTP_ERROR_REGEX.containsMatchIn(throwable.message.orEmpty())) ServerError
                    else NetworkFailure

                else -> Unknown
            }
        }

        private fun fromStatus(code: Int): DownloadError = when (code) {
            404, 410 -> NotFound
            401, 403 -> AccessDenied
            429 -> RateLimited
            else -> ServerError
        }
    }
}
