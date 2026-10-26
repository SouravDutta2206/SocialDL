package com.socialdl.src.extraction.twitter

import com.socialdl.src.core.model.Platform
import com.socialdl.src.extraction.api.PlatformCapabilities
import com.socialdl.src.extraction.api.PlaceholderExtractor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Twitter/X extractor backed by syndication API via Chaquopy (§6).
 * Real implementation is built out in Phase 6.
 */
@Singleton
class TwitterExtractor @Inject constructor() : PlaceholderExtractor() {

    override val platform: Platform = Platform.TWITTER

    override val capabilities: PlatformCapabilities = PlatformCapabilities(
        supportsCarousels = true,
        supportsLoginGatedContent = false,
        needsWebView = false,
        typicalLatencyMs = 800L,
        showsFormatPicker = false // Best quality downloaded directly
    )
}
