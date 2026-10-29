package com.socialdl.src.core.download

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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

    /** Reconciled as failed after process death / system stop. */
    data object Interrupted : DownloadError("Download was interrupted")

    /** Catch-all for unexpected errors (full diagnostics in logs only). */
    data object Unknown : DownloadError("Something went wrong while downloading")

    companion object {
        private val HTTP_ERROR_REGEX = Regex("""HTTP error (\d{3})""")

        fun from(throwable: Throwable): DownloadError {
            if (throwable is CancellationException) return Interrupted
            val message = throwable.message.orEmpty()
            return when (throwable) {
                is HttpStatusException -> when (throwable.code) {
                    404, 410 -> NotFound
                    401, 403 -> AccessDenied
                    429 -> RateLimited
                    else -> ServerError
                }

                is RangeInvalidException,
                is RangeNotSatisfiableException,
                is RangeNotSupportedException -> ServerError

                is SecurityException -> StoragePermission

                is SocketTimeoutException,
                is UnknownHostException,
                is ConnectException,
                is SocketException -> NetworkFailure

                is IOException -> when {
                    message.contains("ENOSPC", ignoreCase = true) ||
                        message.contains("No space left", ignoreCase = true) -> StorageFull
                    HTTP_ERROR_REGEX.containsMatchIn(message) -> ServerError
                    else -> NetworkFailure
                }

                else -> Unknown
            }
        }
    }
}
