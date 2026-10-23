package com.socialdl.src.extraction.api

/**
 * Declares capabilities and UI behaviors for a platform's extractor (§3).
 */
data class PlatformCapabilities(
    /** True if the platform supports multi-media carousel posts (Instagram, Twitter). */
    val supportsCarousels: Boolean = false,

    /** True if the platform supports downloading gated/auth-protected content with credentials. */
    val supportsLoginGatedContent: Boolean = false,

    /** True if extraction requires a rendering WebView (Threads). */
    val needsWebView: Boolean = false,

    /** Typical latency in milliseconds — UI can show "resolving via browser..." for slow extractors. */
    val typicalLatencyMs: Long = 1000L,

    /**
     * True if selecting this item's download action should open the format selection sheet.
     * YouTube is true (many streams); Instagram/Twitter/Threads are false (direct single format).
     */
    val showsFormatPicker: Boolean = false,
)
