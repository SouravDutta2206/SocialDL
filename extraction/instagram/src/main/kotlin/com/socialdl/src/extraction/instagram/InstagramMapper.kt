package com.socialdl.src.extraction.instagram

import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.model.Platform
import com.socialdl.src.extraction.api.mapCarouselMediaItems
import com.socialdl.src.extraction.api.singleDirectFormat

/**
 * Pure DTO → model mapping (no Android/Python dependencies), so fixture tests can
 * cover carousel/image/video shapes without booting Chaquopy.
 *
 * Instagram exposes no quality variants, so every media element maps to exactly
 * one direct CDN format; carousels become N separate cards.
 */
internal suspend fun mapInstagramItems(
    dto: InstagramResponseDto,
    sourceUrl: String,
    storeThumbnail: suspend (String?) -> String?,
): List<MediaItem> {
    val title = dto.caption?.trim()?.takeIf { it.isNotEmpty() } ?: "Instagram Post"

    return mapCarouselMediaItems(
        items = dto.items,
        platform = Platform.INSTAGRAM,
        sourceUrl = sourceUrl,
        title = title,
        author = dto.owner,
        storeThumbnail = storeThumbnail,
        directUrlOf = { it.url },
        isVideoOf = { it.media_type.equals("video", ignoreCase = true) },
        posterUrlOf = { it.thumbnail_url },
        durationOf = { it.duration },
        cacheImageDirectUrl = false,
        videoFormatsOf = { index, _, directUrl ->
            listOf(
                singleDirectFormat(
                    id = "video_${index + 1}",
                    directUrl = directUrl,
                    isVideo = true,
                    label = "Video",
                )
            )
        },
        imageFormatsOf = { index, _, directUrl ->
            listOf(
                singleDirectFormat(
                    id = "image_${index + 1}",
                    directUrl = directUrl,
                    isVideo = false,
                    label = "Image",
                )
            )
        },
    )
}
