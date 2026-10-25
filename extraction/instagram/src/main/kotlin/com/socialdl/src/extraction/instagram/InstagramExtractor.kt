package com.socialdl.src.extraction.instagram

import com.socialdl.src.core.model.Platform
import com.socialdl.src.extraction.api.ExtractionError
import com.socialdl.src.extraction.api.ExtractionResult
import com.socialdl.src.extraction.api.MediaExtractor
import com.socialdl.src.extraction.api.PlatformCapabilities
import com.socialdl.src.extraction.api.UrlPlatformResolver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Instagram extractor backed by Instaloader via Chaquopy (§5).
 * Real implementation is built out in Phase 5.
 */
@Singleton
class InstagramExtractor @Inject constructor() : MediaExtractor {

    override val platform: Platform = Platform.INSTAGRAM

    override val capabilities: PlatformCapabilities = PlatformCapabilities(
        supportsCarousels = true,
        supportsLoginGatedContent = true,
        needsWebView = false,
        typicalLatencyMs = 1500L,
        showsFormatPicker = false // Direct CDN URL download
    )

    override fun supports(url: String): Boolean {
        return UrlPlatformResolver.resolvePlatform(url) == Platform.INSTAGRAM
    }

    override suspend fun extract(url: String): ExtractionResult {
        // Placeholder for Phase 5 implementation.
        // Returns a typed error instead of TODO() which would throw NotImplementedError
        // into the caller's coroutine.
        return ExtractionResult.Error(
            ExtractionError.ContentUnavailable("Instagram extraction is coming soon.")
        )
    }
}
