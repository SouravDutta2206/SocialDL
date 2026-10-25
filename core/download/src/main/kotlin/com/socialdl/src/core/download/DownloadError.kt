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

    /** Format requires muxing but no audio track could be resolved. */
    data object MissingAudioStream : DownloadError("This format needs merging but no audio track was available")

    /** Transfer-level network failure (timeout, DNS, connection, socket). */
    data object NetworkFailure : DownloadError("Network error — check your connection and try again")

    /** Server rejected the download (HTTP error status). */
    data object ServerError : DownloadError("The server rejected the download")

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
                is SocketTimeoutException,
                is UnknownHostException,
                is ConnectException,
                is SocketException -> NetworkFailure

                is IOException -> if (HTTP_ERROR_REGEX.containsMatchIn(message)) ServerError else NetworkFailure

                else -> Unknown
            }
        }
    }
}
