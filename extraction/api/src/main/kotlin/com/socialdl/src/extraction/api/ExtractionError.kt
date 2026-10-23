package com.socialdl.src.extraction.api

/**
 * Sealed hierarchy of errors that can occur during media extraction.
 * Enables granular UI error states (e.g. login prompt on AuthWallDetected,
 * "No media found" on text-only tweets, retry on RateLimited).
 */
sealed class ExtractionError(
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause) {
    /** The URL is not supported by any registered extractor. */
    data class UnsupportedUrl(
        val url: String,
        override val message: String = "Unsupported URL: $url"
    ) : ExtractionError(message)

    /** Network failure during extraction (DNS, timeout, connection reset). */
    data class NetworkFailure(
        override val message: String = "Network failure during extraction",
        override val cause: Throwable? = null
    ) : ExtractionError(message, cause)

    /**
     * Content requires a logged-in session (age restriction, private post, member-only).
     * Triggers the "Sign in with WebView / Instagram" action in the UI.
     */
    data class AuthWallDetected(
        val platform: com.socialdl.src.core.model.Platform = com.socialdl.src.core.model.Platform.UNKNOWN,
        val detail: String? = null,
        override val message: String = "Login required to access this content"
    ) : ExtractionError(message)

    /** Platform rate limit / anti-bot challenge hit. */
    data class RateLimited(
        val retryAfterSeconds: Int? = null,
        override val message: String = "Rate limited by platform. Please wait before retrying."
    ) : ExtractionError(message)

    /** Post was deleted, made private, or does not exist. */
    data class ContentUnavailable(
        val reason: String = "Content unavailable or deleted",
        override val message: String = reason
    ) : ExtractionError(message)

    /** Content loaded successfully but contains no downloadable media (e.g. text-only tweet). */
    data class NoDownloadableMedia(
        override val message: String = "This post contains no downloadable media."
    ) : ExtractionError(message)

    /** Failed to parse metadata / JSON from the extractor. */
    data class ParsingFailed(
        override val message: String = "Failed to parse media details",
        override val cause: Throwable? = null
    ) : ExtractionError(message, cause)

    /** Extraction timed out. */
    data class Timeout(
        override val message: String = "Extraction timed out"
    ) : ExtractionError(message)
}
