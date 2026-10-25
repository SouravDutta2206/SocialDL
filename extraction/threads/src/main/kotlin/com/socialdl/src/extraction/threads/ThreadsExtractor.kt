package com.socialdl.src.extraction.threads

import com.socialdl.src.core.model.Platform
import com.socialdl.src.extraction.api.ExtractionError
import com.socialdl.src.extraction.api.ExtractionResult
import com.socialdl.src.extraction.api.MediaExtractor
import com.socialdl.src.extraction.api.PlatformCapabilities
import com.socialdl.src.extraction.api.UrlPlatformResolver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Threads extractor backed by headless Chromium WebView DOM-extraction pipeline (§7).
 * Real implementation is built out in Phase 7.
 */
@Singleton
class ThreadsExtractor @Inject constructor() : MediaExtractor {

    override val platform: Platform = Platform.THREADS

    override val capabilities: PlatformCapabilities = PlatformCapabilities(
        supportsCarousels = true,
        supportsLoginGatedContent = false,
        needsWebView = true,
        typicalLatencyMs = 6000L,
        showsFormatPicker = false
    )

    override fun supports(url: String): Boolean {
        return UrlPlatformResolver.resolvePlatform(url) == Platform.THREADS
    }

    override suspend fun extract(url: String): ExtractionResult {
        // Placeholder for Phase 7 implementation.
        // Returns a typed error instead of TODO() which would throw NotImplementedError
        // into the caller's coroutine.
        return ExtractionResult.Error(
            ExtractionError.ContentUnavailable("Threads extraction is coming soon.")
        )
    }
}
