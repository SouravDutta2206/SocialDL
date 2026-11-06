package com.socialdl.src.extraction.twitter

import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MediaContainer
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.model.Platform
import kotlin.math.roundToInt

/**
 * Pure DTO → model mapping (no Android/Python dependencies), so fixture tests can
 * cover single video/album/GIF/mixed-carousel shapes without booting Chaquopy.
 *
 * Mapping rules:
 * - one [MediaItem] card per media element (photos, videos, GIFs); carousels get
 *   1-based [MediaItem.carouselIndex] values;
 * - videos carry every mp4 variant as a [MediaFormat], best-first (the bridge
 *   already sorts by pixels → bitrate; this re-sorts defensively by height so
 *   `videoFormats.first()` stays the best quality the UI downloads);
 * - animated GIFs are mp4 loops: mapped as [FormatKind.VIDEO] labelled "GIF
 *   (MP4)" rather than a new format kind (spec Phase 6 — identical download
 *   mechanics);
 * - containers follow the CDN URL (jpg/webp/png/mp4) instead of hardcoding.
 */
internal suspend fun mapTwitterItems(
    dto: TwitterResponseDto,
    sourceUrl: String,
    storeThumbnail: suspend (String?) -> String?,
): List<MediaItem> {
    if (dto.items.isEmpty()) return emptyList()

    val isCarousel = dto.items.size > 1
    val title = dto.caption?.trim()?.takeIf { it.isNotEmpty() } ?: "Twitter/X Post"

    return dto.items.mapNotNull { item ->
        val directUrl = item.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val mediaType = item.media_type.orEmpty().lowercase()
        val isGif = mediaType == "animated_gif"
        val isVideo = mediaType == "video"
        val thumbnailUrl = storeThumbnail(item.thumbnail_url)
            ?: item.thumbnail_url
            ?: directUrl
        val carouselIndex = if (isCarousel) item.index.takeIf { it > 0 } else null

        if (isVideo || isGif) {
            val formats = videoFormats(item, directUrl, isGif)
            MediaItem(
                platform = Platform.TWITTER,
                sourceUrl = sourceUrl,
                title = title,
                author = dto.owner,
                durationSeconds = item.duration?.roundToInt()?.takeIf { it > 0 },
                thumbnailUrl = thumbnailUrl,
                isCarousel = isCarousel,
                carouselIndex = carouselIndex,
                videoFormats = formats,
            )
        } else {
            val container = MediaContainer.fromUrl(directUrl, MediaContainer.JPEG)
            MediaItem(
                platform = Platform.TWITTER,
                sourceUrl = sourceUrl,
                title = title,
                author = dto.owner,
                thumbnailUrl = thumbnailUrl,
                isCarousel = isCarousel,
                carouselIndex = carouselIndex,
                imageFormats = listOf(
                    MediaFormat(
                        id = "image_${item.index}",
                        kind = FormatKind.IMAGE,
                        container = container.extension.uppercase(),
                        label = "Photo",
                        directUrl = directUrl,
                        requiresMuxing = false,
                    )
                ),
            )
        }
    }
}

private fun videoFormats(
    item: TwitterItemDto,
    directUrl: String,
    isGif: Boolean,
): List<MediaFormat> {
    val variants = item.variants.mapNotNull { variant ->
        val url = variant.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val container = MediaContainer.fromUrl(url, MediaContainer.MP4)
        MediaFormat(
            id = "video_${item.index}_${variant.quality ?: url.hashCode()}",
            kind = FormatKind.VIDEO,
            container = container.extension.uppercase(),
            label = formatLabel(variant, isGif),
            height = variant.height?.takeIf { it > 0 },
            directUrl = url,
            requiresMuxing = false,
        )
    }.sortedByDescending { it.height ?: 0 }

    if (variants.isNotEmpty()) return variants

    // Defensive: the bridge always supplies variants for video/GIF elements,
    // but an older/partial payload must still map to a downloadable format.
    val container = MediaContainer.fromUrl(directUrl, MediaContainer.MP4)
    return listOf(
        MediaFormat(
            id = "video_${item.index}",
            kind = FormatKind.VIDEO,
            container = container.extension.uppercase(),
            label = if (isGif) "GIF (MP4)" else "Video",
            directUrl = directUrl,
            requiresMuxing = false,
        )
    )
}

private fun formatLabel(variant: TwitterVariantDto, isGif: Boolean): String {
    if (isGif) return "GIF (MP4)"
    val resLabel = variant.res_label?.takeIf { it.isNotBlank() }
    val quality = variant.quality?.takeIf { it.isNotBlank() && it != resLabel }
    return when {
        resLabel != null && quality != null -> "$resLabel ($quality)"
        resLabel != null -> resLabel
        quality != null -> quality
        else -> "Video"
    }
}
