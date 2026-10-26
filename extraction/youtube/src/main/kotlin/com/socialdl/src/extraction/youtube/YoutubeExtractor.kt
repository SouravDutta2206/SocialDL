package com.socialdl.src.extraction.youtube

import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.common.util.ThumbnailCache
import com.socialdl.src.core.common.util.ensureHttps
import com.socialdl.src.core.cookies.CookieStore
import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.model.MuxStrategy
import com.socialdl.src.core.model.Platform
import com.socialdl.src.core.node.NodeBinaryProvider
import com.socialdl.src.core.python.PythonEngine
import com.socialdl.src.core.python.PythonExecutionException
import com.socialdl.src.extraction.api.ExtractionError
import com.socialdl.src.extraction.api.ExtractionResult
import com.socialdl.src.extraction.api.MediaExtractor
import com.socialdl.src.extraction.api.PlatformCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class YtDlpJsDiagnostics(
    val runtime: String? = null,
    val path: String? = null,
    val version: String? = null,
    val exec_ok: Boolean? = null,
    val min_supported: String? = null,
    val ejs_package: Boolean? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
internal data class YtDlpNormalizedResponse(
    val id: String? = null,
    val webpage_url: String? = null,
    val title: String? = null,
    val uploader: String? = null,
    /** yt-dlp reports duration as a float (e.g. 212.05) — declare Double, round at mapping. */
    val duration: Double? = null,
    val thumbnail_url: String? = null,
    val formats: List<YtDlpFormatDto> = emptyList(),
    val error: String? = null,
    val detail: String? = null,
    /** yt-dlp's own collected progress/warning lines — diagnostics only, on error envelopes. */
    val debug_log: String? = null,
    /**
     * Present ONLY when the JS runtime chain is degraded (§2.6 troubleshooting):
     * node invisible, exec-failing, solver distribution missing, or yt-dlp logged
     * challenge-solving failures. The bridge omits it on healthy runs.
     */
    val js_diagnostics: YtDlpJsDiagnostics? = null,
    val is_playlist: Boolean = false,
    val entries: List<YtDlpNormalizedResponse> = emptyList(),
)

@Serializable
internal data class YtDlpFormatDto(
    val format_id: String,
    val ext: String? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** yt-dlp reports fps as a float (e.g. 29.97) — declare Double, round at mapping. */
    val fps: Double? = null,
    val abr: Double? = null,
    val asr: Int? = null,
    val audio_channels: Int? = null,
    val tbr: Double? = null,
    val dynamic_range: String? = null,
    val filesize: Long? = null,
    val filesize_approx: Long? = null,
    val url: String? = null,
    val downloader_options: JsonElement? = null,
)

/**
 * YouTube extractor backed by yt-dlp via Chaquopy (§4).
 * Pure metadata resolution — streams are fetched and muxed in Kotlin/FFmpeg.
 */
@Singleton
class YoutubeExtractor @Inject constructor(
    private val pythonEngine: PythonEngine,
    private val nodeBinaryProvider: NodeBinaryProvider,
    private val thumbnailCache: ThumbnailCache,
    private val cookieStore: CookieStore,
) : MediaExtractor {

    private val tag = "YoutubeExtractor"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override val platform: Platform = Platform.YOUTUBE

    override val capabilities: PlatformCapabilities = PlatformCapabilities(
        supportsCarousels = false,
        supportsLoginGatedContent = true,
        needsWebView = false,
        typicalLatencyMs = 1500L,
        showsFormatPicker = true
    )

    override suspend fun extract(url: String): ExtractionResult {
        val normalizedUrl = url.trim().ensureHttps()
        if (!supports(normalizedUrl)) {
            return ExtractionResult.Error(ExtractionError.UnsupportedUrl(url))
        }

        val nodeDir = nodeBinaryProvider.binaryDir()
        // the extracted native runtime paths are owned by NativeRuntime on
        // the Kotlin side — passed to the bridge so Python never re-derives
        // filesDir or rebuilds LD_LIBRARY_PATH with drift-prone merge semantics.
        val runtimePaths = nodeBinaryProvider.resolveRuntime()
        Logger.d(
            tag,
            "Extracting YouTube metadata for $normalizedUrl (nodeDir=$nodeDir, binDir=${runtimePaths?.binDir})"
        )

        return try {
            // §2.6 bridge: decrypt the stored jar into a throwaway cacheDir file for
            // the duration of this one PythonEngine call, or pass null when the user
            // never signed in — missing cookies must not become a new failure mode.
            val rawJson = cookieStore.withYoutubeCookieFile { cookieFilePath: String? ->
                pythonEngine.run(
                    module = "yt_dlp_bridge.extract",
                    function = "extract",
                    normalizedUrl,
                    cookieFilePath,
                    nodeDir,
                    runtimePaths?.binDir,
                    runtimePaths?.ldLibraryPath
                ) { it }
            }

            parseYtDlpJson(rawJson, normalizedUrl)
        } catch (e: TimeoutCancellationException) {
            Logger.e(tag, "Extraction timed out for $url", e)
            ExtractionResult.Error(ExtractionError.Timeout())
        } catch (e: CancellationException) {
            // User cancel or structured cancellation — must never be converted to
            // a user-facing "parse failed"; let the coroutine unwind.
            throw e
        } catch (e: PythonExecutionException) {
            // The traceback stays in logs; the user gets a generic parse message.
            Logger.e(tag, "Extraction failed for $url", e)
            ExtractionResult.Error(ExtractionError.ParsingFailed("Couldn't process YouTube's response"))
        } catch (e: Exception) {
            Logger.e(tag, "Extraction failed for $url", e)
            ExtractionResult.Error(ExtractionError.ParsingFailed(e.message ?: "Unknown extraction error"))
        }
    }

    internal suspend fun parseYtDlpJson(rawJson: String, sourceUrl: String): ExtractionResult {
        val parsed = try {
            json.decodeFromString<YtDlpNormalizedResponse>(rawJson)
        } catch (e: Exception) {
            Logger.e(tag, "Failed to deserialize yt-dlp JSON", e)
            return ExtractionResult.Error(ExtractionError.ParsingFailed("Malformed yt-dlp JSON: ${e.message}"))
        }

        parsed.js_diagnostics?.let { diag ->
            Logger.e(
                "JsRuntime",
                "yt-dlp JS runtime chain DEGRADED — n/sig challenge solving will fail and " +
                    "stream formats may be silently missing.\n" +
                    "  runtime=${diag.runtime ?: "<none visible>"} " +
                    "version=${diag.version ?: "?"} exec_ok=${diag.exec_ok} " +
                    "min_supported=${diag.min_supported ?: "?"} " +
                    "ejs_package=${diag.ejs_package}\n" +
                    "  path=${diag.path ?: "<none>"}\n" +
                    "  warnings:\n    " + diag.warnings.joinToString("\n    ") + "\n" +
                    "Troubleshooting (§2.5.2 hot-swap / yt-dlp update regression): verify " +
                    "core/node's bundled libnode.so is executable at nativeLibraryDir, its version is >= " +
                    "${diag.min_supported ?: "22.0.0"}, and extract.py's _resolve_node_runtime() " +
                    "locates it (bin/node symlink → libnode.so → PATH). Watch for this tag: " +
                    "adb logcat -s SocialDL:JsRuntime"
            )
        }

        if (parsed.error != null) {
            // Log the envelope verbatim (error text only, never cookie values) — this
            // is the ground truth needed to distinguish "cookies not loaded" from
            // "session rejected" from "age-gate even with cookies" in the wild.
            Logger.w(tag, "yt-dlp error envelope: ${parsed.error} | ${parsed.detail}")
            parsed.debug_log?.takeIf { it.isNotBlank() }?.let {
                Logger.w(tag, "yt-dlp debug_log:\n$it")
            }
            return when (parsed.error) {
                "auth_wall" -> ExtractionResult.Error(ExtractionError.AuthWallDetected(Platform.YOUTUBE, parsed.detail ?: "Sign-in required"))
                "rate_limited" -> ExtractionResult.Error(ExtractionError.RateLimited())
                "network" -> ExtractionResult.Error(
                    ExtractionError.NetworkFailure(message = parsed.detail ?: "Network failure during extraction")
                )
                else -> ExtractionResult.Error(ExtractionError.ContentUnavailable(parsed.detail ?: "Content unavailable"))
            }
        }

        if (parsed.is_playlist && parsed.entries.isNotEmpty()) {
            val mediaItems = coroutineScope {
                parsed.entries.mapIndexed { index, entryDto ->
                    async(Dispatchers.Default) {
                        val cleanUrl = entryDto.webpage_url
                            ?: entryDto.id?.let { "https://www.youtube.com/watch?v=$it" }
                            ?: sourceUrl.substringBefore("&list").substringBefore("?list")
                        mapDtoToMediaItem(entryDto, cleanUrl, carouselIndex = index + 1)
                    }
                }.awaitAll()
            }
            return ExtractionResult.Success(mediaItems)
        } else {
            val singleCleanUrl = parsed.webpage_url
                ?: parsed.id?.let { "https://www.youtube.com/watch?v=$it" }
                ?: sourceUrl.substringBefore("&list").substringBefore("?list")
            val singleItem = mapDtoToMediaItem(parsed, singleCleanUrl, carouselIndex = null)
            return ExtractionResult.Success(listOf(singleItem))
        }
    }

    private suspend fun mapDtoToMediaItem(
        dto: YtDlpNormalizedResponse,
        itemUrl: String,
        carouselIndex: Int?
    ): MediaItem {
        val duration = dto.duration?.roundToInt() ?: 0
        val cachedThumb = thumbnailCache.store(dto.thumbnail_url) ?: dto.thumbnail_url

        // 1. Partition formats into audio-only, progressive (audio+video), and video-only (§4.2)
        val audioFormats = mutableListOf<MediaFormat>()
        val progressiveFormats = mutableListOf<MediaFormat>()
        val videoOnlyDtos = mutableListOf<YtDlpFormatDto>()

        for (fDto in dto.formats) {
            val hasVideo = fDto.vcodec != null && fDto.vcodec != "none"
            val hasAudio = fDto.acodec != null && fDto.acodec != "none"

            when {
                !hasVideo && hasAudio -> {
                    audioFormats.add(mapAudioFormat(fDto, duration))
                }
                hasVideo && hasAudio -> {
                    progressiveFormats.add(mapProgressiveFormat(fDto, duration))
                }
                hasVideo && !hasAudio -> {
                    videoOnlyDtos.add(fDto)
                }
            }
        }

        // Sort audio formats by bitrate descending
        audioFormats.sortByDescending { it.approxSizeBytes ?: 0L }

        // 2. Map video-only formats and precompute paired audio format (§4.2 + §4.4)
        val bestAudio = audioFormats.firstOrNull()
        val videoFormats = mutableListOf<MediaFormat>()

        // Add progressive formats first (already muxed)
        videoFormats.addAll(progressiveFormats)

        for (vDto in videoOnlyDtos) {
            videoFormats.add(mapVideoOnlyFormat(vDto, duration, bestAudio))
        }

        // Sort video formats by height descending, then fps, then size — using typed
        // fields, never parsing display strings.
        videoFormats.sortWith(
            compareByDescending<MediaFormat> { it.height ?: 0 }
                .thenByDescending { it.fps ?: 0 }
                .thenByDescending { it.approxSizeBytes ?: 0L }
        )

        // Derive final lists from the raw parsed buckets (not the post-mutation lists):
        // - no video formats at all → placeholder video so JIT resolution has a target
        //   (audio-only items would otherwise be silently masked);
        // - no audio formats → placeholder audio.
        val finalVideoFormats = if (videoFormats.isEmpty()) {
            listOf(MediaFormat.PLACEHOLDER_BEST_VIDEO)
        } else {
            videoFormats
        }

        val finalAudioFormats = if (audioFormats.isEmpty()) {
            listOf(MediaFormat.PLACEHOLDER_BEST_AUDIO)
        } else {
            audioFormats
        }

        return MediaItem(
            platform = Platform.YOUTUBE,
            sourceUrl = itemUrl,
            title = dto.title ?: "YouTube Video",
            author = dto.uploader ?: "Unknown",
            durationSeconds = duration,
            thumbnailUrl = cachedThumb,
            videoFormats = finalVideoFormats,
            audioFormats = finalAudioFormats,
            isCarousel = false,
            carouselIndex = carouselIndex
        )
    }

    private fun mapAudioFormat(dto: YtDlpFormatDto, durationSeconds: Int): MediaFormat {
        val container = dto.ext?.uppercase() ?: "M4A"
        // Do not guess a numeric bitrate: label the reported abr when present, fall back
        // to tbr marked as approximate, and omit the number entirely when neither exists.
        // (Previously opus streams without abr were mislabeled "128kbps".)
        val reportedAbr = dto.abr?.toInt()
        val label = when {
            reportedAbr != null -> "${reportedAbr}kbps $container"
            dto.tbr != null -> "~${dto.tbr.toInt()}kbps $container"
            else -> container
        }
        val sampleRate = dto.asr?.let {
            if (it % 1000 == 0) "${it / 1000}kHz" else String.format(java.util.Locale.US, "%.1fkHz", it / 1000.0)
        }
        val size = calculateApproxSize(dto, durationSeconds)

        return MediaFormat(
            id = dto.format_id,
            kind = FormatKind.AUDIO,
            container = container,
            label = label,
            codec = dto.acodec,
            approxSizeBytes = size.first,
            isSizeEstimated = size.second,
            sampleRate = sampleRate,
            audioChannels = dto.audio_channels,
            directUrl = dto.url,
            requiresMuxing = false,
            httpChunkSizeBytes = extractChunkSize(dto)
        )
    }

    private fun mapProgressiveFormat(dto: YtDlpFormatDto, durationSeconds: Int): MediaFormat {
        val container = dto.ext?.uppercase() ?: "MP4"
        val label = buildVideoLabel(dto)
        val size = calculateApproxSize(dto, durationSeconds)

        return MediaFormat(
            id = dto.format_id,
            kind = FormatKind.VIDEO,
            container = container,
            label = label,
            codec = dto.vcodec,
            approxSizeBytes = size.first,
            isSizeEstimated = size.second,
            fps = dto.fps?.roundToInt(),
            height = dto.height,
            isHdr = isHdrFormat(dto.dynamic_range),
            directUrl = dto.url,
            requiresMuxing = false,
            httpChunkSizeBytes = extractChunkSize(dto)
        )
    }

    private fun mapVideoOnlyFormat(
        dto: YtDlpFormatDto,
        durationSeconds: Int,
        bestAudio: MediaFormat?
    ): MediaFormat {
        val container = dto.ext?.uppercase() ?: "MP4"
        val label = buildVideoLabel(dto)
        val (videoBytes, isEstimated) = calculateApproxSize(dto, durationSeconds)
        val audioBytes = bestAudio?.approxSizeBytes
        val totalSize = if (videoBytes != null && audioBytes != null) {
            videoBytes + audioBytes
        } else {
            videoBytes
        }

        val isHdr = isHdrFormat(dto.dynamic_range)
        val muxStrategy = determineMuxStrategy(dto.vcodec, bestAudio?.codec, isHdr)

        return MediaFormat(
            id = dto.format_id,
            kind = FormatKind.VIDEO,
            container = container,
            label = label,
            codec = dto.vcodec,
            approxSizeBytes = totalSize,
            isSizeEstimated = isEstimated || (bestAudio?.isSizeEstimated == true),
            fps = dto.fps?.roundToInt(),
            height = dto.height,
            isHdr = isHdr,
            directUrl = dto.url,
            requiresMuxing = true,
            pairedAudioFormatId = bestAudio?.id,
            muxStrategy = muxStrategy,
            httpChunkSizeBytes = extractChunkSize(dto)
        )
    }

    private fun buildVideoLabel(dto: YtDlpFormatDto): String {
        val res = when {
            dto.height != null && dto.width != null -> "${dto.height}P (${dto.width}x${dto.height})"
            dto.height != null -> "${dto.height}P"
            else -> "Video"
        }
        val fpsStr = dto.fps?.roundToInt()?.takeIf { it > 30 }?.let { " ${it}fps" } ?: ""
        val hdrStr = if (isHdrFormat(dto.dynamic_range)) " HDR" else ""
        return "$res$fpsStr$hdrStr"
    }

    private fun isHdrFormat(dynamicRange: String?): Boolean {
        if (dynamicRange == null) return false
        return !dynamicRange.equals("SDR", ignoreCase = true)
    }

    private fun determineMuxStrategy(vcodec: String?, acodec: String?, isHdr: Boolean): MuxStrategy {
        val vc = vcodec?.lowercase() ?: ""
        val ac = acodec?.lowercase() ?: ""

        val isH264 = vc.startsWith("avc1") || vc.startsWith("h264")
        val isAac = ac.contains("mp4a") || ac.contains("aac") || ac.contains("m4a")

        return if (isH264 && isAac) {
            MuxStrategy.REMUX_COPY
        } else {
            MuxStrategy.TRANSCODE_AUDIO_ONLY
        }
    }

    private fun calculateApproxSize(dto: YtDlpFormatDto, durationSeconds: Int): Pair<Long?, Boolean> {
        if (dto.filesize != null && dto.filesize > 0) {
            return Pair(dto.filesize, false)
        }
        if (dto.filesize_approx != null && dto.filesize_approx > 0) {
            return Pair(dto.filesize_approx, true)
        }
        if (dto.tbr != null && dto.tbr > 0 && durationSeconds > 0) {
            val estimated = (dto.tbr * 1000.0 / 8.0 * durationSeconds).toLong()
            return Pair(estimated, true)
        }
        return Pair(null, false)
    }

    private fun extractChunkSize(dto: YtDlpFormatDto): Long? {
        val options = dto.downloader_options?.jsonObject ?: return null
        return options["http_chunk_size"]?.jsonPrimitive?.longOrNull
    }
}
