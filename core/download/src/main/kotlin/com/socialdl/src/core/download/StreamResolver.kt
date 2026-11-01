package com.socialdl.src.core.download

import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.database.entity.DownloadJobEntity
import com.socialdl.src.core.model.DownloadStatus
import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MediaContainer
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.model.MuxStrategy
import com.socialdl.src.core.settings.SettingsRepository
import com.socialdl.src.extraction.api.ExtractionResult
import com.socialdl.src.extraction.api.ExtractorRegistry
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject

/** Effective stream configuration after optional JIT resolution of a placeholder format. */
internal data class ResolvedStreams(
    val directUrl: String,
    val audioDirectUrl: String,
    val requiresMuxing: Boolean,
    val videoChunkSize: Long,
    val audioChunkSize: Long,
    val muxStrategy: MuxStrategy?,
    val container: String,
    val formatKind: FormatKind,
    val formatLabel: String,
    val formatId: String,
)

internal sealed interface ResolveOutcome {
    data class Ready(val streams: ResolvedStreams) : ResolveOutcome

    /** A terminal failure. The caller records it. */
    data class Failed(val error: DownloadError) : ResolveOutcome

    /** The row is gone or no longer active. Nothing is recorded. */
    data object Inactive : ResolveOutcome
}

/**
 * Resolves the streams a job should download, either from the values persisted at
 * enqueue time or by re-extracting a placeholder / expired direct URL.
 *
 * Every failure is reported as an outcome instead of being written here, so the
 * worker owns all Room failure writes for the pipeline.
 */
class StreamResolver @Inject constructor(
    private val extractorRegistry: ExtractorRegistry,
    private val jobRepository: DownloadJobRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val tag = "StreamResolver"

    /**
     * Writes the initial DOWNLOADING(0f) progress row; returns [ResolveOutcome.Failed]
     * on any resolution failure, and [ResolveOutcome.Inactive] when the row is no
     * longer active.
     *
     * Invariant: a JIT-resolved stream always has the job's requested
     * [FormatKind]; an unparseable stored kind fails as
     * [DownloadError.InvalidFormatKind], and a missing resolved kind fails as
     * [DownloadError.FormatUnavailable] instead of substituting another kind.
     */
    internal suspend fun resolve(job: DownloadJobEntity): ResolveOutcome {
        val jobId = job.id
        Logger.i(
            tag,
            "[$jobId] Acquiring streams: container=${job.container}, requiresMuxing=${job.requiresMuxing}, isPlaceholder=${job.isPlaceholder}"
        )

        // Fail closed on corrupt/legacy kind metadata: FormatKind.from() defaults
        // to VIDEO, which could turn an audio job into a video export. The enqueue
        // path always writes a valid kind, so this only affects damaged rows.
        val requestedKind = FormatSelector.requestedKindOrNull(job.formatKind)
        if (requestedKind == null) {
            Logger.e(
                tag,
                "[$jobId] Stored formatKind '${job.formatKind}' is missing or invalid for " +
                    "${job.sourceUrl} — failing closed"
            )
            return ResolveOutcome.Failed(DownloadError.InvalidFormatKind)
        }

        // The first transition doubles as the "still active" guard: a row already
        // FAILED by startup reconciliation, or deleted by cancel, matches zero
        // rows and the pipeline must not start downloading for it.
        val started = jobRepository.registerProgress(
            id = jobId,
            status = DownloadStatus.DOWNLOADING,
            progress = 0f,
            downloadedBytes = 0L,
            totalBytes = job.totalBytes
        )
        if (!started) {
            Logger.i(tag, "[$jobId] Job is no longer active at start; aborting before download")
            return ResolveOutcome.Inactive
        }

        var effectiveDirectUrl = job.directUrl
        var effectiveAudioDirectUrl = job.audioDirectUrl
        var effectiveRequiresMuxing = job.requiresMuxing
        var effectiveVideoChunkSize = job.videoChunkSize
        var effectiveAudioChunkSize = job.audioChunkSize
        var effectiveMuxStrategy = job.muxStrategy?.let { strategy ->
            // Unparseable stored value must NOT silently fall through to a null
            // strategy: MuxArgs.build(null) transcodes audio instead of copying.
            // REMUX_COPY (c:v copy) is the closest-to-intent recovery.
            runCatching { MuxStrategy.valueOf(strategy) }
                .getOrElse {
                    Logger.w(tag, "[$jobId] Unparseable stored muxStrategy '$strategy' — defaulting to REMUX_COPY")
                    MuxStrategy.REMUX_COPY
                }
        }
        var effectiveContainer = job.container
        var effectiveFormatKind = requestedKind
        var effectiveFormatLabel = job.formatLabel
        var effectiveFormatId = job.formatId
        var resolvedViaExtraction = false
        val forceH264 = runCatching { settingsRepository.forceH264Transcode.first() }
            .getOrDefault(false)

        // Resolve when the job is a placeholder, its signed URL expired, or a
        // legacy row has no container to derive an extension/MIME from.
        if (job.isPlaceholder || effectiveDirectUrl.isNullOrBlank() || effectiveContainer.isBlank()) {
            Logger.i(tag, "[$jobId] JIT resolving stream formats for: ${job.sourceUrl}")

            val extractor = extractorRegistry.resolve(job.sourceUrl)
            if (extractor == null) {
                Logger.e(tag, "[$jobId] No extractor available for ${job.sourceUrl}")
                return ResolveOutcome.Failed(DownloadError.NoExtractor)
            }

            when (val result = extractor.extract(job.sourceUrl)) {
                is ExtractionResult.Success -> {
                    val resolvedItem = result.items.firstOrNull()
                    if (resolvedItem == null) {
                        Logger.e(tag, "[$jobId] No media item returned for ${job.sourceUrl}")
                        return ResolveOutcome.Failed(DownloadError.ExtractionFailed)
                    }

                    // Refresh the stream within the requested kind only: an audio
                    // placeholder must never become a video download (or vice versa).
                    // Prefer the exact persisted format id (same stream the user
                    // picked) over "first of kind", which may silently upgrade or
                    // downgrade the selection after a URL refresh.
                    val resolvedFormat = FormatSelector.select(
                        item = resolvedItem,
                        requestedKind = requestedKind,
                        exactFormatId = job.formatId,
                        allowExact = !job.isPlaceholder,
                    )

                    // Defensive invariant: selection must stay in the requested kind
                    // and yield a direct URL before any bytes are downloaded.
                    if (resolvedFormat == null ||
                        !FormatSelector.isUsable(resolvedFormat, requestedKind)
                    ) {
                        Logger.e(
                            tag,
                            "[$jobId] Format unavailable for ${job.sourceUrl}: " +
                                "requested=$requestedKind, resolvedId=${resolvedFormat?.id}, " +
                                "resolvedKind=${resolvedFormat?.kind}, " +
                                "image=${resolvedItem.imageFormats.size}, " +
                                "video=${resolvedItem.videoFormats.size}, " +
                                "audio=${resolvedItem.audioFormats.size}, " +
                                "exactFormatId=${job.formatId}"
                        )
                        return ResolveOutcome.Failed(DownloadError.FormatUnavailable)
                    }

                    effectiveDirectUrl = resolvedFormat.directUrl
                    effectiveRequiresMuxing = resolvedFormat.requiresMuxing
                    effectiveVideoChunkSize = resolvedFormat.httpChunkSizeBytes ?: 0L
                    effectiveMuxStrategy = resolvedFormat.muxStrategy
                    effectiveFormatKind = resolvedFormat.kind
                    effectiveFormatLabel = resolvedFormat.label
                    effectiveFormatId = resolvedFormat.id

                    // Only accept an explicit container when it is actually
                    // producible for this stream; otherwise adopt the codec-derived
                    // container instead of relabeling bytes (P0-5/P0-6).
                    val autoContainer = MediaContainer.recommendedFor(resolvedFormat)
                    val requestedContainer = job.container
                        .takeIf { it.isNotBlank() && !it.equals("Default", ignoreCase = true) }
                    effectiveContainer = requestedContainer
                        ?.takeIf { MediaContainer.isCompatible(resolvedFormat, it) }
                        ?.let { MediaContainer.extensionOf(it) }
                        ?: autoContainer.extension
                    effectiveContainer = effectiveContainer.uppercase(Locale.ROOT)

                    // Force-H.264 applies only to muxed, non-HDR streams: a full
                    // re-encode of HDR would destroy the dynamic range.
                    if (forceH264 && resolvedFormat.requiresMuxing && !resolvedFormat.isHdr) {
                        effectiveContainer = MediaContainer.MP4.extension.uppercase(Locale.ROOT)
                        effectiveMuxStrategy = MuxStrategy.FORCE_H264
                    }

                    if (resolvedFormat.requiresMuxing) {
                        val pairedAudio = resolvedItem.audioFormats.find { it.id == resolvedFormat.pairedAudioFormatId }
                            ?: resolvedItem.audioFormats.firstOrNull()
                        effectiveAudioDirectUrl = pairedAudio?.directUrl
                        effectiveAudioChunkSize = pairedAudio?.httpChunkSizeBytes ?: 0L
                    }
                    resolvedViaExtraction = true
                }
                is ExtractionResult.Error -> {
                    Logger.e(tag, "[$jobId] JIT stream resolution failed: ${result.cause.message}")
                    return ResolveOutcome.Failed(DownloadError.ExtractionFailed)
                }
            }
        }

        if (effectiveDirectUrl.isNullOrBlank()) {
            Logger.e(tag, "[$jobId] Missing direct download URL")
            return ResolveOutcome.Failed(DownloadError.MissingStream)
        }

        // Persist resolved metadata with a targeted UPDATE so export derives
        // extension/MIME/RELATIVE_PATH from the stream actually downloaded. A zero
        // row count means the user cancelled while extraction was running.
        if (resolvedViaExtraction) {
            val persisted = jobRepository.updateResolvedMetadata(
                id = jobId,
                container = effectiveContainer,
                formatId = effectiveFormatId,
                formatKind = effectiveFormatKind,
                formatLabel = effectiveFormatLabel
            )
            if (!persisted) return ResolveOutcome.Inactive
        }

        // P0-3 gate: a mux-required format with no audio track must fail before any
        // bytes are downloaded — never silently fall through to a video-only export.
        if (effectiveRequiresMuxing && effectiveAudioDirectUrl.isNullOrBlank()) {
            Logger.e(tag, "[$jobId] Format requires muxing but no audio stream was resolved")
            return ResolveOutcome.Failed(DownloadError.MissingAudioStream)
        }

        return ResolveOutcome.Ready(
            ResolvedStreams(
                directUrl = effectiveDirectUrl,
                // Non-mux jobs legitimately have no audio URL (DownloadManager stores
                // null); the mux path is already gated above on requiresMuxing having
                // a non-blank audio URL, so orEmpty() can never be observed as a URL.
                audioDirectUrl = effectiveAudioDirectUrl.orEmpty(),
                requiresMuxing = effectiveRequiresMuxing,
                videoChunkSize = effectiveVideoChunkSize,
                audioChunkSize = effectiveAudioChunkSize,
                muxStrategy = effectiveMuxStrategy,
                container = effectiveContainer,
                formatKind = effectiveFormatKind,
                formatLabel = effectiveFormatLabel,
                formatId = effectiveFormatId
            )
        )
    }
}

/**
 * Chooses the stream to download for a requested [FormatKind].
 *
 * The requested kind is an invariant: this resolver only ever returns a format
 * from the matching kind list, never a substitute from another list. An audio
 * request whose item exposes only video formats returns `null` so the worker can
 * fail with [DownloadError.FormatUnavailable] before downloading any bytes,
 * rather than silently saving a file of the wrong media kind.
 *
 * Resolution order:
 * 1. When [allowExact] is true and [exactFormatId] names a format of
 *    [requestedKind], that exact format is returned (it is the stream the user
 *    originally selected).
 * 2. Otherwise the first format of [requestedKind] is returned.
 * 3. If there is no format of [requestedKind], `null` is returned.
 */
internal object FormatSelector {

    fun select(
        item: MediaItem,
        requestedKind: FormatKind,
        exactFormatId: String?,
        allowExact: Boolean,
    ): MediaFormat? {
        val candidates = formatsOf(item, requestedKind)
        if (allowExact && !exactFormatId.isNullOrBlank()) {
            candidates.firstOrNull { it.id == exactFormatId }?.let { return it }
        }
        return candidates.firstOrNull()
    }

    /**
     * Final defensive gate before any bytes are downloaded: the resolved format
     * must exist, belong to [requestedKind], and carry a direct URL.
     */
    fun isUsable(format: MediaFormat?, requestedKind: FormatKind): Boolean =
        format != null && format.kind == requestedKind && !format.directUrl.isNullOrBlank()

    /**
     * Parses the persisted job kind for the worker. Returns `null` when the
     * stored value is blank or unrecognized, so the caller can fail closed
     * instead of guessing [FormatKind.VIDEO] and risking a cross-kind export.
     */
    fun requestedKindOrNull(storedKind: String?): FormatKind? = FormatKind.fromOrNull(storedKind)

    private fun formatsOf(item: MediaItem, kind: FormatKind): List<MediaFormat> = when (kind) {
        FormatKind.IMAGE -> item.imageFormats
        FormatKind.AUDIO -> item.audioFormats
        FormatKind.VIDEO -> item.videoFormats
    }
}
