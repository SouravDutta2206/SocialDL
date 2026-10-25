package com.socialdl.src.extraction.twitter

import com.socialdl.src.core.model.Platform
import com.socialdl.src.extraction.api.ExtractionError
import com.socialdl.src.extraction.api.ExtractionResult
import com.socialdl.src.extraction.api.MediaExtractor
import com.socialdl.src.extraction.api.PlatformCapabilities
import com.socialdl.src.extraction.api.UrlPlatformResolver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Twitter/X extractor backed by syndication API via Chaquopy (§6).
 * Real implementation is built out in Phase 6.
 */
@Singleton
class TwitterExtractor @Inject constructor() : MediaExtractor {

    override val platform: Platform = Platform.TWITTER

    override val capabilities: PlatformCapabilities = PlatformCapabilities(
        supportsCarousels = true,
        supportsLoginGatedContent = false,
        needsWebView = false,
        typicalLatencyMs = 800L,
        showsFormatPicker = false // Best quality downloaded directly
    )

    override fun supports(url: String): Boolean {
        return UrlPlatformResolver.resolvePlatform(url) == Platform.TWITTER
    }

    override suspend fun extract(url: String): ExtractionResult {
        // Placeholder for Phase 6 implementation.
        // Returns a typed error instead of TODO() which would throw NotImplementedError
        // into the caller's coroutine.
        return ExtractionResult.Error(
            ExtractionError.ContentUnavailable("Twitter extraction is coming soon.")
        )
    }
}
