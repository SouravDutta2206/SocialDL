package com.socialdl.src.core.model

import kotlinx.serialization.Serializable

/**
 * Represents a single resolved media post/video.
 *
 * Every extractor — regardless of platform — returns this same DTO shape,
 * so the UI never needs platform-specific branches.
 *
 * NOTE: Python's MediaItem (one media element within a post) is NOT the same as
 * this class (the whole post/card). The Kotlin mapper regroups Python items into
 * this class's format lists. See §2.3.1 naming collision note.
 */
@Serializable
data class MediaItem(
    val platform: Platform,
    val sourceUrl: String,
    val title: String,
    val author: String? = null,

    /** Duration in seconds. Null for image-only items. */
    val durationSeconds: Int? = null,

    /** Always a local URI after ThumbnailCache.store() runs during extraction. */
    val thumbnailUrl: String? = null,

    /** True when the source post contains multiple media elements (carousel). */
    val isCarousel: Boolean = false,

    /** Index within a carousel, if applicable. */
    val carouselIndex: Int? = null,

    /** Available video formats/streams, sorted by quality descending. */
    val videoFormats: List<MediaFormat> = emptyList(),

    /** Available audio formats/streams, sorted by bitrate descending. */
    val audioFormats: List<MediaFormat> = emptyList(),

    /** Image formats — Instagram photo posts, Twitter photo tweets. */
    val imageFormats: List<MediaFormat> = emptyList(),
) {
    val isPlaceholder: Boolean
        get() = videoFormats.any { it.isPlaceholder } || audioFormats.any { it.isPlaceholder }
}
