package com.socialdl.src.extraction.threads

import com.socialdl.src.core.model.Platform
import com.socialdl.src.extraction.api.PlatformCapabilities
import com.socialdl.src.extraction.api.PlaceholderExtractor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Threads extractor backed by headless Chromium WebView DOM-extraction pipeline (§7).
 * Real implementation is built out in Phase 7.
 */
@Singleton
class ThreadsExtractor @Inject constructor() : PlaceholderExtractor() {

    override val platform: Platform = Platform.THREADS

    override val capabilities: PlatformCapabilities = PlatformCapabilities(
        supportsCarousels = true,
        supportsLoginGatedContent = false,
        needsWebView = true,
        typicalLatencyMs = 6000L,
        showsFormatPicker = false
    )
}
