package com.socialdl.src.extraction.instagram

import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MediaContainer
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.model.Platform
import kotlin.math.roundToInt

/**
 * Pure DTO → model mapping (no Android/Python dependencies), so fixture tests can
 * cover carousel/image/video shapes without booting Chaquopy.
 */
internal suspend fun mapInstagramItems(
    dto: InstagramResponseDto,
    sourceUrl: String,
    storeThumbnail: suspend (String?) -> String?,
): List<MediaItem> {
    if (dto.items.isEmpty()) return emptyList()

    // Filter before computing the carousel shape: a blank slide is not
    // downloadable, so "1 real + 1 broken" is a single item, not a carousel.
    val validItems = dto.items.filter { !it.url.isNullOrBlank() }
    if (validItems.isEmpty()) return emptyList()

    val isCarousel = validItems.size > 1
    val title = dto.caption?.trim()?.takeIf { it.isNotEmpty() } ?: "Instagram Post"

    return validItems.mapIndexedNotNull { index, item ->
        val directUrl = item.url!!.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
        val isVideo = item.media_type.equals("video", ignoreCase = true)
        // Only an explicit image poster may become a thumbnail. A video card must
        // never fall back to its .mp4 directUrl: Coil cannot decode mp4 and the
        // card would stay a permanently black tile.
        val rawThumb = item.thumbnail_url?.takeIf { it.isNotBlank() }
        val thumbnailUrl = rawThumb?.let { storeThumbnail(it) ?: it }
            ?: if (!isVideo) directUrl else null
        val itemNumber = index + 1

        // The CDN URL carries the real media extension (jpg/webp/png/mp4). Never
        // hardcode JPEG: a WebP image saved as .jpg/image/jpeg is mislabeled.
        val container = MediaContainer.fromUrl(
            url = directUrl,
            fallback = if (isVideo) MediaContainer.MP4 else MediaContainer.JPEG
        )

        val format = MediaFormat(
            id = "${if (isVideo) "video" else "image"}_$itemNumber",
            kind = if (isVideo) FormatKind.VIDEO else FormatKind.IMAGE,
            container = container.extension.uppercase(),
            label = if (isVideo) "Video" else "Image",
            directUrl = directUrl,
            requiresMuxing = false,
        )

        MediaItem(
            platform = Platform.INSTAGRAM,
            sourceUrl = sourceUrl,
            title = title,
            author = dto.owner,
            durationSeconds = item.duration?.roundToInt()?.takeIf { it > 0 },
            thumbnailUrl = thumbnailUrl,
            isCarousel = isCarousel,
            carouselIndex = if (isCarousel) itemNumber else null,
            videoFormats = if (isVideo) listOf(format) else emptyList(),
            imageFormats = if (!isVideo) listOf(format) else emptyList(),
        )
    }
}
