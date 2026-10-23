package com.socialdl.src.core.model

/**
 * Supported social media platforms.
 * Adding a new platform means adding one entry here,
 * one extractor module, and one Hilt binding — nothing else.
 */
enum class Platform {
    YOUTUBE,
    INSTAGRAM,
    TWITTER,
    THREADS,
    UNKNOWN,
}
