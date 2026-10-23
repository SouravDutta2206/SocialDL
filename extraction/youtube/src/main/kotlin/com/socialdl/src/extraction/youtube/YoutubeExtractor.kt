package com.socialdl.src.extraction.youtube

import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.common.util.ThumbnailCache
import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.model.MuxStrategy
import com.socialdl.src.core.model.Platform
import com.socialdl.src.core.node.NodeBinaryProvider
import com.socialdl.src.core.python.PythonEngine
import com.socialdl.src.extraction.api.ExtractionError
import com.socialdl.src.extraction.api.ExtractionResult
import com.socialdl.src.extraction.api.MediaExtractor
import com.socialdl.src.extraction.api.PlatformCapabilities
import com.socialdl.src.extraction.api.UrlPlatformResolver
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class YtDlpNormalizedResponse(
    val title: String? = null,
    val uploader: String? = null,
    val duration: Int? = null,
    val thumbnail_url: String? = null,
    val formats: List<YtDlpFormatDto> = emptyList(),
    val error: String? = null,
    val detail: String? = null,
)

@Serializable
internal data class YtDlpFormatDto(
    val format_id: String,
    val ext: String? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
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

    override fun supports(url: String): Boolean {
        return UrlPlatformResolver.resolvePlatform(url) == Platform.YOUTUBE
    }

    override suspend fun extract(url: String): ExtractionResult {
        val trimmed = url.trim()
        val normalizedUrl = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("ttps://") -> "h$trimmed"
            else -> "https://$trimmed"
        }
        if (!supports(normalizedUrl)) {
            return ExtractionResult.Error(ExtractionError.UnsupportedUrl(url))
        }

        val nodeDir = nodeBinaryProvider.binaryDir()
        Logger.d(tag, "Extracting YouTube metadata for $normalizedUrl (nodeDir=$nodeDir)")

        return try {
            val rawJson = pythonEngine.run(
                module = "yt_dlp_bridge.extract",
                function = "extract",
                normalizedUrl,
                null, // cookieFilePath if present
                nodeDir
            ) { it }

            parseYtDlpJson(rawJson, normalizedUrl)
        } catch (e: Exception) {
            Logger.e(tag, "Extraction failed for $url", e)
            val message = e.message ?: "Unknown extraction error"
            if (message.contains("timed out", ignoreCase = true)) {
                ExtractionResult.Error(ExtractionError.Timeout())
            } else {
                ExtractionResult.Error(ExtractionError.ParsingFailed(message))
            }
        }
    }

    internal suspend fun parseYtDlpJson(rawJson: String, sourceUrl: String): ExtractionResult {
        val parsed = try {
            json.decodeFromString<YtDlpNormalizedResponse>(rawJson)
        } catch (e: Exception) {
            Logger.e(tag, "Failed to deserialize yt-dlp JSON", e)
            return ExtractionResult.Error(ExtractionError.ParsingFailed("Malformed yt-dlp JSON: ${e.message}"))
        }

        if (parsed.error != null) {
            return when (parsed.error) {
                "auth_wall" -> ExtractionResult.Error(ExtractionError.AuthWallDetected(Platform.YOUTUBE, parsed.detail ?: "Sign-in required"))
                else -> ExtractionResult.Error(ExtractionError.ContentUnavailable(parsed.detail ?: "Content unavailable"))
            }
        }

        val duration = parsed.duration ?: 0
        val cachedThumb = thumbnailCache.store(parsed.thumbnail_url) ?: parsed.thumbnail_url

        // 1. Partition formats into audio-only, progressive (audio+video), and video-only (§4.2)
        val audioFormats = mutableListOf<MediaFormat>()
        val progressiveFormats = mutableListOf<MediaFormat>()
        val videoOnlyDtos = mutableListOf<YtDlpFormatDto>()

        for (dto in parsed.formats) {
            val hasVideo = dto.vcodec != null && dto.vcodec != "none"
            val hasAudio = dto.acodec != null && dto.acodec != "none"

            when {
                !hasVideo && hasAudio -> {
                    audioFormats.add(mapAudioFormat(dto, duration))
                }
                hasVideo && hasAudio -> {
                    progressiveFormats.add(mapProgressiveFormat(dto, duration))
                }
                hasVideo && !hasAudio -> {
                    videoOnlyDtos.add(dto)
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

        for (dto in videoOnlyDtos) {
            videoFormats.add(mapVideoOnlyFormat(dto, duration, bestAudio))
        }

        // Sort video formats by height descending, then fps descending
        videoFormats.sortWith(
            compareByDescending<MediaFormat> { extractHeight(it.label) }
                .thenByDescending { it.fps ?: 0 }
                .thenByDescending { it.approxSizeBytes ?: 0L }
        )

        val mediaItem = MediaItem(
            platform = Platform.YOUTUBE,
            sourceUrl = sourceUrl,
            title = parsed.title ?: "YouTube Video",
            author = parsed.uploader ?: "Unknown",
            durationSeconds = duration,
            thumbnailUrl = cachedThumb,
            videoFormats = videoFormats,
            audioFormats = audioFormats,
            isCarousel = false
        )

        return ExtractionResult.Success(listOf(mediaItem))
    }

    private fun mapAudioFormat(dto: YtDlpFormatDto, durationSeconds: Int): MediaFormat {
        val container = dto.ext?.uppercase() ?: "M4A"
        val abr = dto.abr?.toInt() ?: 128
        val label = "${abr}kbps $container"
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
            fps = dto.fps,
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
            fps = dto.fps,
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
        val fpsStr = if (dto.fps != null && dto.fps > 30) " ${dto.fps}fps" else ""
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

    private fun extractHeight(label: String): Int {
        val match = Regex("""(\d+)P""").find(label)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
