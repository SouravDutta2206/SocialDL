package com.socialdl.src.extraction.instagram

import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.common.util.ThumbnailCache
import com.socialdl.src.core.common.util.ensureHttps
import com.socialdl.src.core.cookies.CookieStore
import com.socialdl.src.core.cookies.InstagramCookieCapture
import com.socialdl.src.core.model.Platform
import com.socialdl.src.core.python.PythonEngine
import com.socialdl.src.core.python.PythonExecutionException
import com.socialdl.src.extraction.api.ExtractionEnvelope
import com.socialdl.src.extraction.api.ExtractionError
import com.socialdl.src.extraction.api.ExtractionResult
import com.socialdl.src.extraction.api.MediaExtractor
import com.socialdl.src.extraction.api.PlatformCapabilities
import com.socialdl.src.extraction.api.decodeEnvelope
import com.socialdl.src.extraction.api.mapErrorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class InstagramResponseDto(
    val shortcode: String? = null,
    val caption: String? = null,
    val owner: String? = null,
    val items: List<InstagramItemDto> = emptyList(),
)

@Serializable
internal data class InstagramItemDto(
    val index: Int = 0,
    val media_type: String? = null,
    val url: String? = null,
    val thumbnail_url: String? = null,
    val duration: Double? = null,
)

/**
 * Instagram extractor backed by gallery-dl via Chaquopy (Phase 5).
 *
 * Pure metadata resolution: each media element becomes one direct CDN URL and
 * exactly one [MediaFormat] (Instagram exposes no quality variants), so the UI
 * skips the format picker (`showsFormatPicker = false`). Carousels map to N
 * separate cards, matching YouTube's playlist behaviour and the existing batch
 * sheet.
 */
@Singleton
class InstagramExtractor @Inject constructor(
    private val pythonEngine: PythonEngine,
    private val cookieStore: CookieStore,
    private val thumbnailCache: ThumbnailCache,
) : MediaExtractor {

    private val tag = "InstagramExtractor"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override val platform: Platform = Platform.INSTAGRAM

    override val capabilities: PlatformCapabilities = PlatformCapabilities(
        supportsCarousels = true,
        supportsLoginGatedContent = true,
        needsWebView = false,
        typicalLatencyMs = 1500L,
        showsFormatPicker = false // Direct CDN URL download
    )

    override suspend fun extract(url: String): ExtractionResult {
        val normalizedUrl = url.trim().ensureHttps()
        if (!supports(normalizedUrl)) {
            return ExtractionResult.Error(ExtractionError.UnsupportedUrl(url))
        }

        Logger.d(tag, "Extracting Instagram metadata for $normalizedUrl (cookies=${cookieStore.hasCookies(Platform.INSTAGRAM)})")

        return try {
            // Hybrid engine routing (Oct 2026):
            // - cookies captured → gallery-dl (works for public + private content)
            // - no cookies → anonymous Instaloader fallback; a failure surfaces the
            //   normal typed error (auth wall / rate limited / unavailable).
            val hasCookies = cookieStore.hasCookies(Platform.INSTAGRAM)
            val envelope = if (hasCookies) {
                cookieStore.withCookieFile(Platform.INSTAGRAM) { cookiePath ->
                    val rawJson = pythonEngine.run(
                        module = "gallery_dl_bridge.extract",
                        function = "extract",
                        normalizedUrl,
                        cookiePath,
                        InstagramCookieCapture.USER_AGENT
                    ) { it }
                    decodeEnvelope(rawJson)
                }
            } else {
                val rawJson = pythonEngine.run(
                    module = "instaloader_bridge.extract",
                    function = "extract",
                    normalizedUrl
                ) { it }
                decodeEnvelope(rawJson)
            }

            when (envelope) {
                is ExtractionEnvelope.Failure -> {
                    Logger.w(tag, "Instagram error envelope: ${envelope.kind} | ${envelope.detail}")
                    envelope.debugLog?.takeIf { it.isNotBlank() }?.let {
                        Logger.w(tag, "Instagram bridge log:\n$it")
                    }
                    ExtractionResult.Error(
                        mapErrorKind(envelope.kind, envelope.detail, Platform.INSTAGRAM)
                    )
                }

                is ExtractionEnvelope.Success -> mapPayload(envelope.payload, normalizedUrl)
            }
        } catch (e: TimeoutCancellationException) {
            Logger.e(tag, "Extraction timed out for $url", e)
            ExtractionResult.Error(ExtractionError.Timeout())
        } catch (e: CancellationException) {
            // User cancel or structured cancellation — never convert to a user-facing
            // failure; let the coroutine unwind (same contract as YoutubeExtractor).
            throw e
        } catch (e: PythonExecutionException) {
            Logger.e(tag, "Extraction failed for $url", e)
            ExtractionResult.Error(ExtractionError.ParsingFailed("Couldn't process Instagram's response"))
        } catch (e: Exception) {
            Logger.e(tag, "Extraction failed for $url", e)
            ExtractionResult.Error(ExtractionError.ParsingFailed(e.message ?: "Unknown extraction error"))
        }
    }

    private suspend fun mapPayload(payload: JsonElement, sourceUrl: String): ExtractionResult {
        val dto = try {
            json.decodeFromJsonElement<InstagramResponseDto>(payload)
        } catch (e: Exception) {
            Logger.e(tag, "Failed to deserialize Instagram JSON", e)
            return ExtractionResult.Error(ExtractionError.ParsingFailed("Malformed Instagram JSON: ${e.message}"))
        }

        if (dto.items.isEmpty()) {
            return ExtractionResult.Error(ExtractionError.NoDownloadableMedia())
        }

        val items = mapInstagramItems(dto, sourceUrl) { thumbnailCache.store(it) }

        return if (items.isEmpty()) {
            ExtractionResult.Error(ExtractionError.NoDownloadableMedia())
        } else {
            ExtractionResult.Success(items)
        }
    }
}
