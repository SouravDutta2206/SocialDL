package com.socialdl.src.core.model

/**
 * Supported social media platforms.
 * Adding a new platform means adding one entry here,
 * one extractor module, and one Hilt binding — nothing else.
 */
enum class Platform(
    /** User-facing product name, e.g. shown in sign-in and auth-wall copy. */
    val displayName: String,
) {
    YOUTUBE("YouTube"),
    INSTAGRAM("Instagram"),
    TWITTER("X"),
    THREADS("Threads"),
    UNKNOWN("Unknown"),
}
