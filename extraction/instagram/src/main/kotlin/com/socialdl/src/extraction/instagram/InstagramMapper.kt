package com.socialdl.src.extraction.instagram

import com.socialdl.src.core.model.FormatKind
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

    val isCarousel = dto.items.size > 1
    val title = dto.caption?.trim()?.takeIf { it.isNotEmpty() } ?: "Instagram Post"

    return dto.items.mapIndexedNotNull { index, item ->
        val directUrl = item.url?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
        val isVideo = item.media_type.equals("video", ignoreCase = true)
        val thumbnailUrl = storeThumbnail(item.thumbnail_url)
            ?: item.thumbnail_url
            ?: directUrl

        val format = MediaFormat(
            id = "${if (isVideo) "video" else "image"}_$index",
            kind = if (isVideo) FormatKind.VIDEO else FormatKind.IMAGE,
            container = if (isVideo) "MP4" else "JPEG",
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
            carouselIndex = if (isCarousel) index + 1 else null,
            videoFormats = if (isVideo) listOf(format) else emptyList(),
            imageFormats = if (!isVideo) listOf(format) else emptyList(),
        )
    }
}
