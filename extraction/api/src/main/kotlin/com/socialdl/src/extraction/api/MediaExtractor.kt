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

    /** Returns true if this extractor can handle the given URL. */
    fun supports(url: String): Boolean

    /**
     * Resolves metadata and downloadable stream formats for the URL.
     * Returns [ExtractionResult.Success] with one or more MediaItem cards,
     * or [ExtractionResult.Error] with typed cause.
     */
    suspend fun extract(url: String): ExtractionResult
}
