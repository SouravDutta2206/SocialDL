package com.socialdl.src.extraction.api

import com.socialdl.src.core.model.Platform
import java.util.regex.Pattern

/**
 * Sniffs and normalizes URLs to determine which [Platform] they belong to.
 * Supports:
 * - Scheme-less URLs (e.g. "youtube.com/watch?v=...")
 * - Mobile subdomains (m.youtube.com, www.instagram.com)
 * - Short links (youtu.be, t.co, threads.net/share)
 * - Domain aliases (twitter.com -> x.com)
 */
object UrlPlatformResolver {

    private val YOUTUBE_REGEX = Pattern.compile(
        """^(?:https?://)?(?:www\.|m\.|music\.)?(?:youtube\.com/(?:watch\?v=|shorts/|embed/|v/|live/|playlist\?)|youtu\.be/)([\w\-]+)""",
        Pattern.CASE_INSENSITIVE
    )

    private val INSTAGRAM_REGEX = Pattern.compile(
        """^(?:https?://)?(?:www\.)?instagram\.com/(?:(?:p|reels?|tv)/([\w\-]+)|stories/([\w.\-]+))""",
        Pattern.CASE_INSENSITIVE
    )

    private val TWITTER_REGEX = Pattern.compile(
        """^(?:https?://)?(?:www\.)?(?:twitter\.com|x\.com)/(?:i(?:/web)?/status|[\w]+/status|status)/([\d]+)""",
        Pattern.CASE_INSENSITIVE
    )

    private val TWITTER_SHORT_REGEX = Pattern.compile(
        """^(?:https?://)?(?:www\.)?t\.co/([\w]+)""",
        Pattern.CASE_INSENSITIVE
    )

    private val THREADS_REGEX = Pattern.compile(
        """^(?:https?://)?(?:www\.)?(?:threads\.net|threads\.com)/(?:@[\w\.\-]+/post/|share/|post/|t/)([\w\-]+)""",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Resolves the [Platform] for a given URL string.
     * Returns [Platform.UNKNOWN] if no pattern matches.
     */
    fun resolvePlatform(url: String): Platform {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return Platform.UNKNOWN

        return when {
            YOUTUBE_REGEX.matcher(trimmed).find() -> Platform.YOUTUBE
            INSTAGRAM_REGEX.matcher(trimmed).find() -> Platform.INSTAGRAM
            TWITTER_REGEX.matcher(trimmed).find() -> Platform.TWITTER
            TWITTER_SHORT_REGEX.matcher(trimmed).find() -> Platform.TWITTER
            THREADS_REGEX.matcher(trimmed).find() -> Platform.THREADS
            // Fallback host check for generalized matching
            containsHost(trimmed, "youtube.com") || containsHost(trimmed, "youtu.be") -> Platform.YOUTUBE
            containsHost(trimmed, "instagram.com") -> Platform.INSTAGRAM
            containsHost(trimmed, "twitter.com") || containsHost(trimmed, "x.com") || containsHost(trimmed, "t.co") -> Platform.TWITTER
            containsHost(trimmed, "threads.net") || containsHost(trimmed, "threads.com") -> Platform.THREADS
            else -> Platform.UNKNOWN
        }
    }

    /**
     * True when [url]'s host is [host] or a subdomain of it (e.g. music.youtube.com
     * for youtube.com). Parses the host with java.net.URI — never substring matching,
     * so redirect-wrapped or lookalike domains (youtube.com.evil.com) cannot hijack
     * resolution.
     */
    private fun containsHost(url: String, host: String): Boolean {
        if (url.contains("://")) {
            // Only real web URLs are platform links: javascript:, intent://,
            // content:// and friends must never resolve to a platform.
            val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
            if (scheme != "http" && scheme != "https") return false
        } else {
            // A scheme without slashes (`javascript:youtube.com/...` or
            // `javascript:443@youtube.com/...`) is not a URL; only allow a true
            // `host:port` prefix (`youtube.com:443/...`).
            if (SCHEME_PREFIX.matcher(url).find() && !HOST_PORT_PREFIX.matcher(url).find()) {
                return false
            }
        }
        val withScheme = if (url.contains("://")) url else "https://$url"
        val uriHost = runCatching {
            java.net.URI(withScheme).host?.lowercase()
        }.getOrNull() ?: return false
        return uriHost == host || uriHost == "www.$host" || uriHost.endsWith(".$host")
    }

    /** Matches any `scheme:` prefix; paired with [HOST_PORT_PREFIX] to keep `host:port`. */
    private val SCHEME_PREFIX = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    private val HOST_PORT_PREFIX = Pattern.compile(
        "^[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)+:\\d+(?:[/?#]|$)"
    )
}
