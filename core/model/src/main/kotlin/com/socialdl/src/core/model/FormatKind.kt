package com.socialdl.src.core.model

/**
 * Classifies a media format.
 * IMAGE is used for Instagram photo posts and Twitter photo tweets.
 */
enum class FormatKind {
    VIDEO,
    AUDIO,
    IMAGE;

    companion object {
        /** Case-insensitive parse of the string stored in Room; null when absent/unknown. */
        fun fromOrNull(value: String?): FormatKind? =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }

        /** Parse with a VIDEO fallback for legacy rows that predate the typed field. */
        fun from(value: String?): FormatKind = fromOrNull(value) ?: VIDEO
    }
}
