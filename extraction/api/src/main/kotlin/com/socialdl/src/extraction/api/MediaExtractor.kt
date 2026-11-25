package com.socialdl.src.extraction.api

import com.socialdl.src.core.model.Platform

/**
 * Unified contract implemented by all platform-specific extractors.
 *
 * Each extractor handles resolving metadata and downloadable direct URLs for its platform.
 * Feature modules only talk to [MediaExtractor] and [ExtractorRegistry], never concrete extractors.
 */
interface MediaExtractor {
    /** The platform handled by this extractor. */
    val platform: Platform

    /** Extractor capability flags and UI descriptors. */
    val capabilities: PlatformCapabilities
        get() = PlatformCapabilities()

    /**
     * Returns true if this extractor can handle the given URL.
     * Default: single-platform resolution via [UrlPlatformResolver] — override
     * only when an extractor handles a platform its [platform] field doesn't name.
     */
    fun supports(url: String): Boolean = UrlPlatformResolver.resolvePlatform(url) == platform

    /**
     * Resolves metadata and downloadable stream formats for the URL.
     * Returns [ExtractionResult.Success] with one or more MediaItem cards,
     * or [ExtractionResult.Error] with typed cause.
     */
    suspend fun extract(url: String): ExtractionResult

    /**
     * Re-resolves [url] ignoring any saved session (cookies).
     *
     * Used for the one retry after the media CDN refuses a URL minted for the
     * signed-in session: public content is reachable anonymously, so a session-
     * bound URL that keeps returning 401/403 can still be downloaded this way.
     * Returns `null` when this extractor has no anonymous mode (the default), so
     * callers skip the attempt instead of repeating the identical request.
     */
    suspend fun extractWithoutSavedSession(url: String): ExtractionResult? = null
}
