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
import kotlinx.coroutines.CancellationException
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

    /** True when [sourceUrl]'s extractor can re-resolve public content without a session. */
    fun supportsAnonymousRetry(sourceUrl: String): Boolean =
        extractorRegistry.resolve(sourceUrl)?.capabilities?.supportsAnonymousRetry == true

    /**
     * Writes the initial DOWNLOADING(0f) progress row; returns [ResolveOutcome.Failed]
     * on any resolution failure, and [ResolveOutcome.Inactive] when the row is no
     * longer active.
     *
     * Invariant: a JIT-resolved stream always has the job's requested
     * [FormatKind]; an unparseable stored kind fails as
     * [DownloadError.InvalidFormatKind], and a missing resolved kind fails as
     * [DownloadError.FormatUnavailable] instead of substituting another kind.
     *
     * @param forceRefresh re-extract even when the row already carries a signed URL.
     *   Used for the one retry after the media CDN refused that URL (401/403): the
     *   stored URL is dead at that point even though it is not blank.
     * @param useSavedSession false re-resolves without the stored cookies. Public
     *   content is reachable anonymously, so a URL the CDN refuses for the
     *   signed-in session can still be obtained this way.
     * @param rejectedFormatId / @param rejectedUrl the stream the CDN refused, so the
     *   retry picks another candidate when one exists instead of repeating the same
     *   rejected request.
     * @param rejectedAudioUrl the audio stream the CDN refused. The paired-audio
     *   choice prefers any other audio before falling back to it.
     */
    internal suspend fun resolve(
        job: DownloadJobEntity,
        forceRefresh: Boolean = false,
        useSavedSession: Boolean = true,
        rejectedFormatId: String? = null,
        rejectedUrl: String? = null,
        rejectedAudioUrl: String? = null,
    ): ResolveOutcome {
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
        // Only FORCE_H264 is meaningful now: stream copy vs re-encode is decided from the
        // probed codecs at download time, so the stored value is read for that one case.
        var effectiveMuxStrategy = job.muxStrategy
            ?.let { runCatching { MuxStrategy.valueOf(it) }.getOrNull() }
            ?.takeIf { it == MuxStrategy.FORCE_H264 }
        var effectiveContainer = job.container
        var effectiveFormatKind = requestedKind
        var effectiveFormatLabel = job.formatLabel
        var effectiveFormatId = job.formatId
        var resolvedViaExtraction = false
        val forceH264 = try {
            settingsRepository.forceH264Transcode.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A settings read failure must not fail the download; default off.
            false
        }

        // Resolve when the job is a placeholder, its signed URL expired or was
        // refused, or a legacy row has no container to derive an extension/MIME from.
        if (forceRefresh ||
            job.isPlaceholder ||
            effectiveDirectUrl.isNullOrBlank() ||
            effectiveContainer.isBlank()
        ) {
            Logger.i(
                tag,
                "[$jobId] JIT resolving stream formats for: ${job.sourceUrl} " +
                    "(forceRefresh=$forceRefresh, savedSession=$useSavedSession, " +
                    "rejectedFormatId=$rejectedFormatId)"
            )

            val extractor = extractorRegistry.resolve(job.sourceUrl)
            if (extractor == null) {
                Logger.e(tag, "[$jobId] No extractor available for ${job.sourceUrl}")
                return ResolveOutcome.Failed(DownloadError.NoExtractor)
            }

            // The anonymous mode is optional: a platform without it simply reruns
            // the normal extraction rather than failing the job.
            val extraction = if (!useSavedSession) {
                extractor.extractWithoutSavedSession(job.sourceUrl) ?: extractor.extract(job.sourceUrl)
            } else {
                extractor.extract(job.sourceUrl)
            }

            when (val result = extraction) {
                is ExtractionResult.Success -> {
                    // Bug #2: a refreshed carousel job must re-resolve ITS slide,
                    // not slide 1. Extraction returns one MediaItem per slide with
                    // a 1-based carouselIndex; older extractors may leave it null,
                    // in which case the list position is the best available match.
                    val resolvedItem = if (job.carouselIndex > 0 && result.items.size > 1) {
                        result.items.firstOrNull { it.carouselIndex == job.carouselIndex }
                            ?: result.items.getOrNull(job.carouselIndex - 1)
                            ?: result.items.firstOrNull()
                    } else {
                        result.items.firstOrNull()
                    }
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
                        rejectedFormatId = rejectedFormatId,
                        rejectedUrl = rejectedUrl,
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
                        // Prefer the paired stream, but never re-request an audio
                        // URL the CDN just refused. Fall back to the paired id,
                        // then to any audio format, only when nothing else exists.
                        val pairedAudio = resolvedItem.audioFormats
                            .firstOrNull {
                                it.id == resolvedFormat.pairedAudioFormatId &&
                                    it.directUrl != rejectedAudioUrl
                            }
                            ?: resolvedItem.audioFormats
                                .firstOrNull { it.directUrl != rejectedAudioUrl }
                            ?: resolvedItem.audioFormats
                                .firstOrNull { it.id == resolvedFormat.pairedAudioFormatId }
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
 *
 * After a CDN refusal ([rejectedFormatId] / [rejectedUrl]), rule 2 prefers a
 * different stream: a candidate whose id and URL are both different, then any
 * candidate whose URL is different, and only then the rejected stream again (a
 * re-extraction hands back the same format under a fresh signature).
 */
internal object FormatSelector {

    fun select(
        item: MediaItem,
        requestedKind: FormatKind,
        exactFormatId: String?,
        allowExact: Boolean,
        rejectedFormatId: String? = null,
        rejectedUrl: String? = null,
    ): MediaFormat? {
        val candidates = formatsOf(item, requestedKind)
        if (allowExact && !exactFormatId.isNullOrBlank()) {
            // An explicit pick keeps its exact format; a re-resolution after a CDN
            // rejection only refreshes that format's signed URL.
            candidates.firstOrNull { it.id == exactFormatId }?.let { return it }
        }
        if (rejectedFormatId == null && rejectedUrl == null) return candidates.firstOrNull()

        // A placeholder's first choice was refused by the CDN. Prefer a different
        // stream (another codec at the same quality) whose URL has not been rejected,
        // then any URL-but-the-rejected-one, and only repeat the rejected stream when
        // the item exposes nothing else.
        return candidates.firstOrNull { it.id != rejectedFormatId && it.directUrl != rejectedUrl }
            ?: candidates.firstOrNull { it.directUrl != rejectedUrl }
            ?: candidates.firstOrNull()
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
