package com.socialdl.src.extraction.instagram

import com.socialdl.src.core.model.Platform
import com.socialdl.src.extraction.api.PlatformCapabilities
import com.socialdl.src.extraction.api.PlaceholderExtractor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Instagram extractor backed by Instaloader via Chaquopy (§5).
 * Real implementation is built out in Phase 5.
 */
@Singleton
class InstagramExtractor @Inject constructor() : PlaceholderExtractor() {

    override val platform: Platform = Platform.INSTAGRAM

    override val capabilities: PlatformCapabilities = PlatformCapabilities(
        supportsCarousels = true,
        supportsLoginGatedContent = true,
        needsWebView = false,
        typicalLatencyMs = 1500L,
        showsFormatPicker = false // Direct CDN URL download
    )
}
