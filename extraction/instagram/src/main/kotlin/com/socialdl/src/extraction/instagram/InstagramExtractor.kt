package com.socialdl.src.extraction.instagram

import android.content.Context
import android.webkit.WebSettings
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.common.util.ThumbnailCache
import com.socialdl.src.core.common.util.ensureHttps
import com.socialdl.src.core.cookies.CookieStore
import com.socialdl.src.core.model.Platform
import com.socialdl.src.core.python.PythonEngine
import com.socialdl.src.core.python.PythonExecutionException
import com.socialdl.src.extraction.api.ExtractionError
import com.socialdl.src.extraction.api.ExtractionJson
import com.socialdl.src.extraction.api.ExtractionResult
import com.socialdl.src.extraction.api.MediaExtractor
import com.socialdl.src.extraction.api.PlatformCapabilities
import com.socialdl.src.extraction.api.runEnvelopeExtraction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
) : MediaExtractor {

    private val tag = "InstagramExtractor"

    /** Only used if the platform API returns nothing (engine hot-swap window). */
    private val FALLBACK_USER_AGENT: String
        get() = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

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

        Logger.d(tag, "Extracting Instagram metadata for $normalizedUrl")

        return runEnvelopeExtraction(
            tag = tag,
            platform = Platform.INSTAGRAM,
            url = url,
            bridgeErrorMessage = "Couldn't process Instagram's response",
            isBridgeException = { it is PythonExecutionException },
            fetchRawJson = {
                // Hybrid engine routing (Oct 2026), decided atomically under the one
                // cookie-jar call:
                // - a decrypted jar exists → gallery-dl (works for public + private content)
                // - no jar → anonymous Instaloader fallback; a failure surfaces the
                //   normal typed error (auth wall / rate limited / unavailable).
                //
                // The previous `jarState()` check followed by `withCookieFile()` was a
                // TOCTOU race: the jar can be cleared/replaced between the two calls.
                // The cookie path handed to the block is the single source of truth.
                cookieStore.withCookieFile(Platform.INSTAGRAM) { cookiePath ->
                    if (cookiePath != null) {
                        // gallery-dl must present the same UA the capture WebView used, or a
                        // UA-bound session looks logged out. Capture never overrides the UA
                        // (§2.6), so the engine default *is* what the capture screen sent.
                        val captureUserAgent = runCatching {
                            WebSettings.getDefaultUserAgent(context)
                        }.getOrNull()?.takeIf { it.isNotBlank() } ?: FALLBACK_USER_AGENT
                        pythonEngine.run(
                            module = "gallery_dl_bridge.extract",
                            function = "extract",
                            normalizedUrl,
                            cookiePath,
                            captureUserAgent
                        ) { it }
                    } else {
                        pythonEngine.run(
                            module = "instaloader_bridge.extract",
                            function = "extract",
                            normalizedUrl
                        ) { it }
                    }
                }
            },
            mapSuccess = { payload -> mapPayload(payload, normalizedUrl) },
        )
    }

    private suspend fun mapPayload(payload: JsonElement, sourceUrl: String): ExtractionResult {
        val dto = try {
            ExtractionJson.decodeFromJsonElement<InstagramResponseDto>(payload)
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
