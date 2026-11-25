package com.socialdl.src.extraction.api

import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MediaContainer
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.model.Platform
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Builds the single downloadable format a CDN-direct platform (Instagram,
 * Twitter/X, Threads) exposes for one media element. The container follows the
 * real URL extension instead of hardcoding one: a WebP saved as `.jpg` would be
 * mislabeled.
 */
fun singleDirectFormat(
    id: String,
    directUrl: String,
    isVideo: Boolean,
    label: String,
    height: Int? = null,
): MediaFormat {
    val container = MediaContainer.fromUrl(
        url = directUrl,
        fallback = if (isVideo) MediaContainer.MP4 else MediaContainer.JPEG,
    )
    return MediaFormat(
        id = id,
        kind = if (isVideo) FormatKind.VIDEO else FormatKind.IMAGE,
        container = container.extension.uppercase(Locale.ROOT),
        label = label,
        height = height,
        directUrl = directUrl,
        requiresMuxing = false,
    )
}

/**
 * Shared DTO → [MediaItem] carousel mapping for platforms whose bridge returns a
 * flat list of media elements (Instagram, Twitter/X, Threads).
 *
 * Platform differences stay in the caller-supplied lambdas: IDs/labels via
 * [videoFormatsOf]/[imageFormatsOf], numbering via [carouselIndexOf], and
 * whether image cards cache their own CDN URL as a thumbnail via
 * [cacheImageDirectUrl].
 *
 * A blank [directUrlOf] element is filtered out **before** the carousel shape is
 * computed, so "1 real slide + 1 broken slide" stays a single card rather than a
 * phantom carousel.
 */
suspend fun <T> mapCarouselMediaItems(
    items: List<T>,
    platform: Platform,
    sourceUrl: String,
    title: String,
    author: String?,
    storeThumbnail: suspend (String?) -> String?,
    directUrlOf: (T) -> String?,
    isVideoOf: (T) -> Boolean,
    posterUrlOf: (T) -> String?,
    durationOf: (T) -> Double? = { null },
    carouselIndexOf: (index: Int, item: T) -> Int = { index, _ -> index + 1 },
    cacheImageDirectUrl: Boolean = false,
    videoFormatsOf: (index: Int, item: T, directUrl: String) -> List<MediaFormat>,
    imageFormatsOf: (index: Int, item: T, directUrl: String) -> List<MediaFormat>,
): List<MediaItem> {
    val validItems = items.filter { !directUrlOf(it).isNullOrBlank() }
    if (validItems.isEmpty()) return emptyList()

    val isCarousel = validItems.size > 1

    return validItems.mapIndexed { index, item ->
        val directUrl = directUrlOf(item)!!
        val isVideo = isVideoOf(item)

        // Only an explicit image poster may become a video thumbnail. A video
        // card must never fall back to its .mp4 directUrl: Coil cannot decode
        // mp4 and the card would stay a permanently black tile.
        val rawThumb = posterUrlOf(item)?.takeIf { it.isNotBlank() }
        val thumbnailUrl = rawThumb?.let { storeThumbnail(it) ?: it }
            ?: if (!isVideo) {
                if (cacheImageDirectUrl) (storeThumbnail(directUrl) ?: directUrl) else directUrl
            } else {
                null
            }

        val carouselIndex = if (isCarousel) carouselIndexOf(index, item) else null
        val durationSeconds = if (isVideo) {
            durationOf(item)?.roundToInt()?.takeIf { it > 0 }
        } else {
            null
        }

        MediaItem(
            platform = platform,
            sourceUrl = sourceUrl,
            title = title,
            author = author,
            durationSeconds = durationSeconds,
            thumbnailUrl = thumbnailUrl,
            isCarousel = isCarousel,
            carouselIndex = carouselIndex,
            videoFormats = if (isVideo) videoFormatsOf(index, item, directUrl) else emptyList(),
            imageFormats = if (!isVideo) imageFormatsOf(index, item, directUrl) else emptyList(),
        )
    }
}
