package com.socialdl.src.extraction.twitter

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
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class TwitterResponseDto(
    val shortcode: String? = null,
    val caption: String? = null,
    val owner: String? = null,
    /** "gallery-dl" or "syndication" — diagnostics only (logged, never rendered). */
    val engine: String? = null,
    val items: List<TwitterItemDto> = emptyList(),
)

@Serializable
internal data class TwitterItemDto(
    val index: Int = 0,
    val media_type: String? = null,
    val url: String? = null,
    val thumbnail_url: String? = null,
    val duration: Double? = null,
    val variants: List<TwitterVariantDto> = emptyList(),
)

@Serializable
internal data class TwitterVariantDto(
    val url: String? = null,
    val res_label: String? = null,
    val quality: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val content_type: String? = null,
)

/**
 * Twitter/X extractor backed by the hybrid Chaquopy bridge (Phase 6).
 *
 * The bridge owns engine routing: a stored Twitter jar sends it to gallery-dl,
 * no jar sends it to the public Syndication CDN, and a gallery-dl failure falls
 * back to syndication transparently. Kotlin only decodes the flat payload and
 * maps it to [com.socialdl.src.core.model.MediaItem] cards.
 *
 * Pure metadata resolution: every media element becomes one direct CDN URL and
 * videos carry their full variant list best-first, so "best quality" is already
 * decided at extraction time (`videoFormats.first()`), and the UI skips the
 * format picker (`showsFormatPicker = false`). Carousels map to N separate
 * cards, matching Instagram's behaviour.
 */
@Singleton
class TwitterExtractor @Inject constructor(
    private val pythonEngine: PythonEngine,
    private val cookieStore: CookieStore,
    private val thumbnailCache: ThumbnailCache,
) : MediaExtractor {

    private val tag = "TwitterExtractor"

    override val platform: Platform = Platform.TWITTER

    override val capabilities: PlatformCapabilities = PlatformCapabilities(
        supportsCarousels = true,
        // gallery-dl with a Twitter jar resolves protected/follow-gated content.
        supportsLoginGatedContent = true,
        needsWebView = false,
        // Slower than syndication-only: the gallery-dl engine bootstraps a
        // session (and may fall back) before answering.
        typicalLatencyMs = 1200L,
        showsFormatPicker = false // Variant sort makes videoFormats.first() the best
    )

    override suspend fun extract(url: String): ExtractionResult {
        val normalizedUrl = url.trim().ensureHttps()
        if (!supports(normalizedUrl)) {
            return ExtractionResult.Error(ExtractionError.UnsupportedUrl(url))
        }

        Logger.d(tag, "Extracting X metadata for $normalizedUrl")

        return runEnvelopeExtraction(
            tag = tag,
            platform = Platform.TWITTER,
            url = url,
            bridgeErrorMessage = "Couldn't process X's response",
            isBridgeException = { it is PythonExecutionException },
            fetchRawJson = {
                // Missing cookies must not introduce a new failure mode: a null jar
                // path flows straight through, and the bridge picks syndication.
                // No UA is pinned: X sessions are not UA-bound, and the capture
                // WebView presents the device's own Chrome UA (risk-control lesson).
                cookieStore.withCookieFile(Platform.TWITTER) { cookiePath ->
                    Logger.d(tag, "X bridge cookies=${cookiePath != null}")
                    // twitter_bridge.extract takes (url, cookiefile) only — no UA arg.
                    pythonEngine.run(
                        module = "twitter_bridge.extract",
                        function = "extract",
                        normalizedUrl,
                        cookiePath
                    ) { it }
                }
            },
            mapSuccess = { payload -> mapPayload(payload, normalizedUrl) },
        )
    }

    private suspend fun mapPayload(payload: JsonElement, sourceUrl: String): ExtractionResult {
        val dto = try {
            ExtractionJson.decodeFromJsonElement<TwitterResponseDto>(payload)
        } catch (e: Exception) {
            Logger.e(tag, "Failed to deserialize Twitter JSON", e)
            return ExtractionResult.Error(
                ExtractionError.ParsingFailed("Malformed Twitter JSON: ${e.message}")
            )
        }

        dto.engine?.let { Logger.d(tag, "X payload resolved via $it engine") }

        if (dto.items.isEmpty()) {
            return ExtractionResult.Error(ExtractionError.NoDownloadableMedia())
        }

        val items = mapTwitterItems(dto, sourceUrl) { thumbnailCache.store(it) }

        return if (items.isEmpty()) {
            ExtractionResult.Error(ExtractionError.NoDownloadableMedia())
        } else {
            ExtractionResult.Success(items)
        }
    }
}
