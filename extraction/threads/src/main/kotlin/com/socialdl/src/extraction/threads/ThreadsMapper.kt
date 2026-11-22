package com.socialdl.src.extraction.threads

import com.socialdl.src.core.common.util.ensureHttps
import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MediaContainer
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.model.Platform
import kotlinx.serialization.Serializable
import java.net.URI
import kotlin.math.roundToInt

@Serializable
internal data class ThreadsExtractionDto(
    val status: String = "error",
    val kind: String = "none",
    val shortcode: String? = null,
    val author: String? = null,
    val caption: String? = null,
    val items: List<ThreadsItemDto> = emptyList(),
)

@Serializable
internal data class ThreadsItemDto(
    val kind: String = "photo",
    val pk: String? = null,
    val url: String? = null,
    val posterUrl: String? = null,
    val w: Int? = null,
    val h: Int? = null,
    val duration: Double? = null,
    val cover: Boolean = false,
)

private val THREADS_AUTHOR_REGEX = Regex("""/@([\w.\-]+)/post/""", RegexOption.IGNORE_CASE)

/**
 * Ensures https:// and normalizes threads.net / bare threads.com URLs to
 * https://www.threads.com so consent cookies and canonical routes line up.
 */
internal fun normalizeThreadsUrl(url: String): String {
    val withScheme = url.trim().ensureHttps()
    val uri = runCatching { URI(withScheme) }.getOrNull() ?: return withScheme
    val host = uri.host?.lowercase() ?: return withScheme
    val canonicalHost = when {
        host == "threads.net" || host == "www.threads.net" || host.endsWith(".threads.net") -> "www.threads.com"
        host == "threads.com" -> "www.threads.com"
        else -> return withScheme
    }
    return runCatching {
        URI(
            "https",
            uri.rawUserInfo,
            canonicalHost,
            uri.port,
            uri.rawPath,
            uri.rawQuery,
            uri.rawFragment
        ).toASCIIString()
    }.getOrElse { withScheme }
}

internal fun extractAuthorFromUrl(url: String): String? =
    THREADS_AUTHOR_REGEX.find(url)?.groupValues?.getOrNull(1)?.let { "@$it" }

/**
 * Pure DTO → model mapping (no Android/WebView dependencies), so JVM unit tests
 * can cover photo, carousel, video, and empty/text-only shapes directly.
 */
internal suspend fun mapThreadsItems(
    dto: ThreadsExtractionDto,
    sourceUrl: String,
    storeThumbnail: suspend (String?) -> String?,
): List<MediaItem> {
    if (dto.status == "no_media" || dto.items.isEmpty()) return emptyList()

    val validItems = dto.items.filter { !it.url.isNullOrBlank() }
    if (validItems.isEmpty()) return emptyList()

    val isCarousel = validItems.size > 1
    val author = dto.author?.trim()?.takeIf { it.isNotEmpty() } ?: extractAuthorFromUrl(sourceUrl)
    val baseTitle = dto.caption?.trim()?.takeIf { it.isNotEmpty() }
        ?: if (dto.kind.equals("video", ignoreCase = true)) "Threads Video" else "Threads Post"

    return validItems.mapIndexed { index, item ->
        val directUrl = item.url!!
        val isVideo = item.kind.equals("video", ignoreCase = true)
        val carouselIndex = if (isCarousel) index + 1 else null

        if (isVideo) {
            // Never pass an .mp4 URL to ThumbnailCache or Coil; only cache an explicit image poster.
            val poster = item.posterUrl?.takeIf { it.isNotBlank() }
            val thumbnailUrl = poster?.let { storeThumbnail(it) ?: it }
            val container = MediaContainer.fromUrl(directUrl, MediaContainer.MP4)
            val height = item.h?.takeIf { it > 0 }
            val label = height?.let { "${container.extension.uppercase()} (${it}p)" } ?: "Video"

            MediaItem(
                platform = Platform.THREADS,
                sourceUrl = sourceUrl,
                title = baseTitle,
                author = author,
                durationSeconds = item.duration?.roundToInt()?.takeIf { it > 0 },
                thumbnailUrl = thumbnailUrl,
                isCarousel = isCarousel,
                carouselIndex = carouselIndex,
                videoFormats = listOf(
                    MediaFormat(
                        id = item.pk?.takeIf { it.isNotBlank() }?.let { "video_$it" } ?: "video_${index + 1}",
                        kind = FormatKind.VIDEO,
                        container = container.extension.uppercase(),
                        label = label,
                        height = height,
                        directUrl = directUrl,
                        requiresMuxing = false,
                    )
                ),
            )
        } else {
            val thumbnailUrl = storeThumbnail(directUrl) ?: directUrl
            val container = MediaContainer.fromUrl(directUrl, MediaContainer.JPEG)
            val width = item.w?.takeIf { it > 0 }
            val height = item.h?.takeIf { it > 0 }
            val label = if (width != null && height != null) {
                "${container.extension.uppercase()} (${width}x${height})"
            } else {
                "Image"
            }

            MediaItem(
                platform = Platform.THREADS,
                sourceUrl = sourceUrl,
                title = baseTitle,
                author = author,
                thumbnailUrl = thumbnailUrl,
                isCarousel = isCarousel,
                carouselIndex = carouselIndex,
                imageFormats = listOf(
                    MediaFormat(
                        id = item.pk?.takeIf { it.isNotBlank() }?.let { "image_$it" } ?: "image_${index + 1}",
                        kind = FormatKind.IMAGE,
                        container = container.extension.uppercase(),
                        label = label,
                        height = height,
                        directUrl = directUrl,
                        requiresMuxing = false,
                    )
                ),
            )
        }
    }
}
