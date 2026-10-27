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

/** Every stream of every kind, for exact `formatId` lookup after JIT re-resolution. */
fun MediaItem.allFormats(): List<MediaFormat> = videoFormats + audioFormats + imageFormats

/**
 * Stable identity key for LazyColumn rows and download-task lookups.
 * Carousel items use their carouselIndex; non-carousel items fall back to the provided
 * list index (or null). ALWAYS build both sides of a lookup with this helper so the
 * formulas cannot drift — desynced keys corrupt LazyColumn identity and batch selection.
 */
fun MediaItem.uiKey(fallbackIndex: Int? = null): String = "${sourceUrl}_${carouselIndex ?: fallbackIndex}"
