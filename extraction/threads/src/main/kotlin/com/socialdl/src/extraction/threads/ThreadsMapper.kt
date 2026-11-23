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
    val loginWall: Boolean = false,
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
    // Rebuild from the raw components instead of the 7-arg URI constructor:
    // that constructor treats `%XX` as literal text and re-encodes it (%25XX).
    return runCatching {
        buildString {
            append("https://")
            if (!uri.rawUserInfo.isNullOrEmpty()) append(uri.rawUserInfo).append('@')
            append(canonicalHost)
            if (uri.port != -1) append(':').append(uri.port)
            append(uri.rawPath.orEmpty())
            if (uri.rawQuery != null) append('?').append(uri.rawQuery)
            if (uri.rawFragment != null) append('#').append(uri.rawFragment)
        }
    }.getOrElse { withScheme }
}

internal fun extractAuthorFromUrl(url: String): String? =
    THREADS_AUTHOR_REGEX.find(url)?.groupValues?.getOrNull(1)?.let { "@$it" }

internal data class ParsedNetscapeCookie(
    val url: String,
    val setCookieHeader: String,
    val name: String = "",
    val path: String = "/",
    /** Host without the leading dot; non-null only for domain cookies. */
    val domain: String? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
) {
    /**
     * `Set-Cookie` value that expires this cookie in the process-global
     * `CookieManager`. `ThreadsExtractor` replays a decrypted jar into that
     * shared store for the duration of one run and must scrub it afterwards.
     */
    val expiredSetCookieHeader: String
        get() = buildString {
            append(name).append("=; Path=").append(path)
            if (domain != null) append("; Domain=.").append(domain)
            append("; Max-Age=0")
            if (secure) append("; Secure")
            if (httpOnly) append("; HttpOnly")
        }
}

/**
 * Parses Netscape `cookies.txt` lines into `(url, Set-Cookie)` pairs suitable
 * for `CookieManager.setCookie(url, value)`, preserving `Domain`, `Path`,
 * `Secure`, and `HttpOnly` attributes.
 *
 * Expired rows are skipped (a jar can outlive its cookies). Netscape's expiry
 * column is epoch seconds; `0` means a session cookie and is kept.
 */
internal fun parseNetscapeCookies(
    lines: List<String>,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
): List<ParsedNetscapeCookie> {
    val result = ArrayList<ParsedNetscapeCookie>(lines.size)
    for (rawLine in lines) {
        // Only strip line endings and leading indentation: `trim()` would also
        // drop the trailing tab of a valid 7-column row whose value is empty.
        val trimmed = rawLine.trimEnd('\r', '\n').trimStart()
        if (trimmed.isEmpty()) continue
        val httpOnly = trimmed.startsWith("#HttpOnly_")
        if (!httpOnly && trimmed.startsWith("#")) continue

        val dataLine = if (httpOnly) trimmed.removePrefix("#HttpOnly_") else trimmed
        val cols = dataLine.split('\t')
        if (cols.size != 7) continue

        val rawDomain = cols[0].trim()
        val host = rawDomain.removePrefix(".")
        val name = cols[5].trim()
        val value = cols[6]
        if (host.isEmpty() || name.isEmpty()) continue

        // Malformed (non-numeric) expiry rows cannot be trusted; skip them.
        val expires = cols[4].trim().toLongOrNull() ?: continue
        // Negative is expired; a positive timestamp at/before now is expired.
        // Zero is a session cookie and is always valid for this replay.
        if (expires < 0L || (expires > 0L && expires <= nowEpochSeconds)) continue

        val includeSubdomains = cols[1].equals("TRUE", ignoreCase = true) || rawDomain.startsWith(".")
        val path = cols[2].trim().ifEmpty { "/" }
        val secure = cols[3].equals("TRUE", ignoreCase = true)
        val scheme = if (secure) "https" else "http"

        val header = buildString {
            append(name).append('=').append(value)
            append("; Path=").append(path)
            if (includeSubdomains) append("; Domain=.").append(host)
            if (secure) append("; Secure")
            if (httpOnly) append("; HttpOnly")
        }
        result += ParsedNetscapeCookie(
            url = "$scheme://$host",
            setCookieHeader = header,
            name = name,
            path = path,
            domain = host.takeIf { includeSubdomains },
            secure = secure,
            httpOnly = httpOnly,
        )
    }
    return result
}

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
