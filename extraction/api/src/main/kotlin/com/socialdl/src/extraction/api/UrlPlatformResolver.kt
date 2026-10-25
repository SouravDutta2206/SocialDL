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
        """^(?:https?://)?(?:www\.|m\.)?(?:youtube\.com/(?:watch\?v=|shorts/|embed/|v/|live/|playlist\?)|youtu\.be/)([\w\-]+)""",
        Pattern.CASE_INSENSITIVE
    )

    private val INSTAGRAM_REGEX = Pattern.compile(
        """^(?:https?://)?(?:www\.)?instagram\.com/(?:p|reels?|tv|stories)/([\w\-]+)""",
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
        """^(?:https?://)?(?:www\.)?(?:threads\.net|threads\.com)/(?:@[\w\.\-]+/post/|share/|post/)([\w\-]+)""",
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

    private fun containsHost(url: String, host: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("://$host") || lower.contains("://www.$host") || lower.startsWith(host) || lower.startsWith("www.$host")
    }
}
