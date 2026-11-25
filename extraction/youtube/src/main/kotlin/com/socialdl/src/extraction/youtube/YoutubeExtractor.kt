package com.socialdl.src.extraction.youtube

import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.common.util.ThumbnailCache
import com.socialdl.src.core.common.util.ensureHttps
import com.socialdl.src.core.cookies.CookieStore
import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.model.Platform
import com.socialdl.src.core.node.NodeBinaryProvider
import com.socialdl.src.core.python.PythonEngine
import com.socialdl.src.core.python.PythonExecutionException
import com.socialdl.src.extraction.api.ExtractionEnvelope
import com.socialdl.src.extraction.api.ExtractionError
import com.socialdl.src.extraction.api.ExtractionJson
import com.socialdl.src.extraction.api.ExtractionResult
import com.socialdl.src.extraction.api.MediaExtractor
import com.socialdl.src.extraction.api.PlatformCapabilities
import com.socialdl.src.extraction.api.runEnvelopeExtraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
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

    /**
     * A large playlist maps every entry concurrently; without a cap that is one network
     * fetch per entry at once. Four keeps the fan-out bounded (the UI only shows one row
     * at a time anyway). Single videos are unaffected.
     */
    private val thumbnailPermits = Semaphore(MAX_CONCURRENT_THUMBNAIL_FETCHES)

    override val platform: Platform = Platform.YOUTUBE

    override val capabilities: PlatformCapabilities = PlatformCapabilities(
        supportsCarousels = false,
        supportsLoginGatedContent = true,
        needsWebView = false,
        typicalLatencyMs = 1500L,
        showsFormatPicker = true,
        // Public videos resolve without a session; the download path retries that
        // way when a cookie-bound stream URL is refused by the CDN.
        supportsAnonymousRetry = true
    )

    override suspend fun extract(url: String): ExtractionResult = extractInternal(url, useSavedSession = true)

    /**
     * Public-content fallback for a refused session-bound URL: yt-dlp runs without
     * the cookie jar. Member-only/private videos fail here with their normal typed
     * error, and the caller falls back to the session-bound retry.
     */
    override suspend fun extractWithoutSavedSession(url: String): ExtractionResult =
        extractInternal(url, useSavedSession = false)

    private suspend fun extractInternal(url: String, useSavedSession: Boolean): ExtractionResult {
        val normalizedUrl = url.trim().ensureHttps()
        if (!supports(normalizedUrl)) {
            return ExtractionResult.Error(ExtractionError.UnsupportedUrl(url))
        }

        val nodeDir = nodeBinaryProvider.binaryDir()
        // the extracted native runtime paths are owned by NativeRuntime on
        // the Kotlin side — passed to the bridge so Python never re-derives
        // filesDir or rebuilds LD_LIBRARY_PATH with drift-prone merge semantics.
        // The bridge calls os.path.join on these, so they must cross as Strings.
        val runtimePaths = nodeBinaryProvider.resolveRuntime()
        val nodeBinDir = runtimePaths?.binDir?.absolutePath
        Logger.d(
            tag,
            "Extracting YouTube metadata for $normalizedUrl " +
                "(savedSession=$useSavedSession, nodeDir=$nodeDir, binDir=$nodeBinDir)"
        )

        return runEnvelopeExtraction(
            tag = tag,
            platform = Platform.YOUTUBE,
            url = url,
            bridgeErrorMessage = "Couldn't process YouTube's response",
            isBridgeException = { it is PythonExecutionException },
            onFailureEnvelope = ::logJsDiagnosticsFromEnvelope,
            fetchRawJson = {
                // §2.6 bridge: decrypt the stored jar into a throwaway cacheDir file
                // for the duration of this one PythonEngine call, or pass null when
                // the user never signed in — missing cookies must not become a new
                // failure mode.
                suspend fun invokeBridge(cookieFilePath: String?): String = runBridge(
                    normalizedUrl = normalizedUrl,
                    cookieFilePath = cookieFilePath,
                    nodeDir = nodeDir,
                    nodeBinDir = nodeBinDir,
                    ldLibraryPath = runtimePaths?.ldLibraryPath,
                )
                if (useSavedSession) {
                    cookieStore.withCookieFile(Platform.YOUTUBE) { invokeBridge(it) }
                } else {
                    invokeBridge(null)
                }
            },
            mapSuccess = { payload -> mapSuccessPayload(payload, normalizedUrl) },
        )
    }

    /** One yt-dlp bridge invocation; [cookieFilePath] is null for an anonymous call. */
    private suspend fun runBridge(
        normalizedUrl: String,
        cookieFilePath: String?,
        nodeDir: String?,
        nodeBinDir: String?,
        ldLibraryPath: String?,
    ): String = pythonEngine.run(
        module = "yt_dlp_bridge.extract",
        function = "extract",
        normalizedUrl,
        cookieFilePath,
        nodeDir,
        nodeBinDir,
        ldLibraryPath
    ) { it }

    /**
     * Envelope-level parse/mapping for one yt-dlp payload. Kept directly testable
     * by [YoutubeExtractorTest]; production goes through the same shared runner.
     */
    internal suspend fun parseYtDlpJson(rawJson: String, sourceUrl: String): ExtractionResult =
        runEnvelopeExtraction(
            tag = tag,
            platform = Platform.YOUTUBE,
            url = sourceUrl,
            bridgeErrorMessage = "Couldn't process YouTube's response",
            isBridgeException = { it is PythonExecutionException },
            onFailureEnvelope = ::logJsDiagnosticsFromEnvelope,
            fetchRawJson = { rawJson },
            mapSuccess = { payload -> mapSuccessPayload(payload, sourceUrl) },
        )

    /** Maps a decoded success payload (single video or playlist) to media items. */
    private suspend fun mapSuccessPayload(
        payload: JsonElement,
        sourceUrl: String,
    ): ExtractionResult {
        val parsed = try {
            ExtractionJson.decodeFromJsonElement<YtDlpNormalizedResponse>(payload)
        } catch (e: Exception) {
            Logger.e(tag, "Failed to deserialize yt-dlp JSON", e)
            return ExtractionResult.Error(ExtractionError.ParsingFailed("Malformed yt-dlp JSON: ${e.message}"))
        }

        parsed.js_diagnostics?.let(::logJsDiagnostics)

        if (parsed.is_playlist) {
            // An empty playlist (private, deleted, or filtered out) must not fall through
            // to the single-video branch and render a placeholder card.
            if (parsed.entries.isEmpty()) {
                return ExtractionResult.Error(ExtractionError.ContentUnavailable("This playlist has no videos"))
            }
            val mediaItems = coroutineScope {
                parsed.entries.mapIndexed { index, entryDto ->
                    async(Dispatchers.Default) {
                        val cleanUrl = entryDto.webpage_url
                            ?: entryDto.id?.let { "https://www.youtube.com/watch?v=$it" }
                            ?: stripListQueryParam(sourceUrl)
                        mapDtoToMediaItem(entryDto, cleanUrl, carouselIndex = index + 1)
                    }
                }.awaitAll()
            }
            return ExtractionResult.Success(mediaItems)
        } else {
            // A single video with no usable formats is not a downloadable video.
            // (Playlist entries above legitimately carry no formats because of
            // extract_flat, so their placeholder cards stay.)
            if (!parsed.hasUsableMedia()) {
                Logger.w(tag, "yt-dlp returned no usable audio/video format for $sourceUrl")
                return ExtractionResult.Error(ExtractionError.NoDownloadableMedia())
            }
            val singleCleanUrl = parsed.webpage_url
                ?: parsed.id?.let { "https://www.youtube.com/watch?v=$it" }
                ?: stripListQueryParam(sourceUrl)
            val singleItem = mapDtoToMediaItem(parsed, singleCleanUrl, carouselIndex = null)
            return ExtractionResult.Success(listOf(singleItem))
        }
    }

    /**
     * Logs yt-dlp's `js_diagnostics` when the failure envelope carries one. The
     * diagnostic rides on the raw failure object, which [decodeEnvelope] keeps in
     * [ExtractionEnvelope.Failure.raw].
     */
    private fun logJsDiagnosticsFromEnvelope(envelope: ExtractionEnvelope.Failure) {
        runCatching {
            envelope.raw?.get("js_diagnostics")
                ?.takeUnless { it is JsonNull }
                ?.let { ExtractionJson.decodeFromJsonElement<YtDlpJsDiagnostics>(it) }
        }.getOrNull()?.let(::logJsDiagnostics)
    }

    private fun logJsDiagnostics(diag: YtDlpJsDiagnostics) {
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

    /** True when at least one format carries an audio or a video stream. */
    private fun YtDlpNormalizedResponse.hasUsableMedia(): Boolean = formats.any { format ->
        val hasVideo = format.vcodec != null && format.vcodec != "none"
        val hasAudio = format.acodec != null && format.acodec != "none"
        hasVideo || hasAudio
    }

    private suspend fun mapDtoToMediaItem(
        dto: YtDlpNormalizedResponse,
        itemUrl: String,
        carouselIndex: Int?
    ): MediaItem {
        val duration = dto.duration?.roundToInt() ?: 0
        val cachedThumb = thumbnailPermits.withPermit {
            thumbnailCache.store(dto.thumbnail_url)
        } ?: dto.thumbnail_url

        // 1. Partition formats into audio-only, progressive (audio+video), and video-only (§4.2)
        val audioDtos = mutableListOf<YtDlpFormatDto>()
        val progressiveFormats = mutableListOf<MediaFormat>()
        val videoOnlyDtos = mutableListOf<YtDlpFormatDto>()

        for (fDto in dto.formats) {
            val hasVideo = fDto.vcodec != null && fDto.vcodec != "none"
            val hasAudio = fDto.acodec != null && fDto.acodec != "none"

            when {
                !hasVideo && hasAudio -> {
                    audioDtos.add(fDto)
                }
                hasVideo && hasAudio -> {
                    progressiveFormats.add(mapVideoFormat(fDto, duration))
                }
                hasVideo && !hasAudio -> {
                    videoOnlyDtos.add(fDto)
                }
            }
        }

        // Rank audio by reported bitrate first (the actual quality signal), then
        // by the mapped format's size estimate as a tie-breaker. Sorting by size
        // alone picked a larger but lower-bitrate stream.
        val audioFormats = audioDtos
            .map { audioDto -> (audioDto.abr ?: audioDto.tbr ?: 0.0) to mapAudioFormat(audioDto, duration) }
            .sortedWith(
                compareByDescending<Pair<Double, MediaFormat>> { it.first }
                    .thenByDescending { it.second.approxSizeBytes ?: 0L }
            )
            .map { it.second }

        // 2. Map video-only formats and precompute paired audio format (§4.2 + §4.4)
        val bestAudio = audioFormats.firstOrNull()
        val videoFormats = mutableListOf<MediaFormat>()

        // Add progressive formats first (already muxed)
        videoFormats.addAll(progressiveFormats)

        for (vDto in videoOnlyDtos) {
            videoFormats.add(mapVideoFormat(vDto, duration, bestAudio))
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
        // (Previously opus streams without abr were mislabeled "128kbps".) Zero or
        // non-positive reports are treated as absent so "0kbps"/"~0kbps" never render.
        val reportedAbr = dto.abr?.takeIf { it.isFinite() }?.roundToInt()?.takeIf { it > 0 }
        val reportedTbr = dto.tbr?.takeIf { it.isFinite() }?.roundToInt()?.takeIf { it > 0 }
        val label = when {
            reportedAbr != null -> "${reportedAbr}kbps $container"
            reportedTbr != null -> "~${reportedTbr}kbps $container"
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

    /**
     * Maps one video format. With [pairedAudio] == null this is a progressive
     * (already muxed) stream; with a paired audio format the stream is video-only
     * and its reported size estimates include the audio bytes it must be muxed with.
     */
    private fun mapVideoFormat(
        dto: YtDlpFormatDto,
        durationSeconds: Int,
        pairedAudio: MediaFormat? = null,
    ): MediaFormat {
        val container = dto.ext?.uppercase() ?: "MP4"
        val label = buildVideoLabel(dto)
        val (videoBytes, isEstimated) = calculateApproxSize(dto, durationSeconds)
        val audioBytes = pairedAudio?.approxSizeBytes
        val totalSize = if (videoBytes != null && audioBytes != null) {
            videoBytes + audioBytes
        } else {
            videoBytes
        }

        return MediaFormat(
            id = dto.format_id,
            kind = FormatKind.VIDEO,
            container = container,
            label = label,
            codec = dto.vcodec,
            approxSizeBytes = totalSize,
            isSizeEstimated = isEstimated || (pairedAudio?.isSizeEstimated == true),
            fps = dto.fps?.roundToInt(),
            height = dto.height,
            isHdr = isHdrFormat(dto.dynamic_range),
            directUrl = dto.url,
            requiresMuxing = pairedAudio != null,
            pairedAudioFormatId = pairedAudio?.id,
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
        // yt-dlp's downloader_options is untrusted JSON: tolerate nulls, arrays,
        // primitives, and nested/non-numeric http_chunk_size instead of throwing
        // a ClassCastException out of extraction.
        val options = dto.downloader_options as? JsonObject ?: return null
        val chunkSize = options["http_chunk_size"] as? JsonPrimitive ?: return null
        return chunkSize.longOrNull?.takeIf { it > 0 }
    }

    private companion object {
        const val MAX_CONCURRENT_THUMBNAIL_FETCHES = 4
    }
}

/**
 * Removes only the ``list`` query parameter from [url], preserving ``v=``, every
 * other parameter, blank values, and the fragment.
 *
 * The previous `substringBefore("?list")` / `substringBefore("&list")` approach
 * turned `watch?list=...&v=ID` into `watch?` (losing the video id) and dropped
 * every parameter after `&list=`.
 */
internal fun stripListQueryParam(url: String): String {
    val fragmentIndex = url.indexOf('#')
    val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
    val withoutFragment = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
    val queryIndex = withoutFragment.indexOf('?')
    if (queryIndex < 0) return url

    val base = withoutFragment.substring(0, queryIndex)
    val params = withoutFragment.substring(queryIndex + 1).split('&')
    val kept = params.filter { parameter ->
        parameter.isNotEmpty() && !parameter.substringBefore('=').equals("list", ignoreCase = true)
    }
    return buildString {
        append(base)
        if (kept.isNotEmpty()) append('?').append(kept.joinToString("&"))
        append(fragment)
    }
}
