package com.socialdl.src.core.download

import com.socialdl.src.core.model.MediaContainer
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MuxStrategy
import java.util.Locale

/** The container a job will produce, plus the mux strategy that overrides codec probing. */
internal data class OutputPlan(
    val container: String,
    val muxStrategy: MuxStrategy?,
)

/**
 * Decides the output container for [format], shared by enqueue and JIT re-resolution.
 *
 * - Force-H.264 wins: muxed, non-HDR video is re-encoded into MP4.
 * - An explicit, producible request is honored.
 * - [preserveBlankForPlaceholder] stores "" for a placeholder so the worker adopts the
 *   JIT-resolved format's native container instead of guessing one at enqueue time.
 * - Everything else uses the codec-derived recommended container.
 */
internal fun resolveOutputPlan(
    format: MediaFormat,
    requestedContainer: String?,
    forceH264Setting: Boolean,
    preserveBlankForPlaceholder: Boolean,
): OutputPlan {
    val requested = requestedContainer
        ?.takeIf { it.isNotBlank() && !it.equals("Default", ignoreCase = true) }
    val applyForceH264 = forceH264Setting && format.requiresMuxing && !format.isHdr
    val container = when {
        applyForceH264 -> MediaContainer.MP4.extension.uppercase(Locale.ROOT)
        requested != null && MediaContainer.isCompatible(format, requested) ->
            MediaContainer.extensionOf(requested).uppercase(Locale.ROOT)
        preserveBlankForPlaceholder && format.isPlaceholder -> ""
        else -> MediaContainer.recommendedFor(format).extension.uppercase(Locale.ROOT)
    }
    val muxStrategy = if (applyForceH264) MuxStrategy.FORCE_H264 else null
    return OutputPlan(container = container, muxStrategy = muxStrategy)
}

/**
 * Chooses the audio stream to mux with a video-only format.
 *
 * When [rejectedAudioUrl] is set (the CDN refused that URL), every other candidate is
 * preferred before falling back to the rejected stream — a re-extraction hands the same
 * format back under a fresh signature. Before any rejection, [pairedAudioFormatId] wins.
 */
internal fun selectPairedAudio(
    audioFormats: List<MediaFormat>,
    pairedAudioFormatId: String?,
    rejectedAudioUrl: String? = null,
): MediaFormat? = if (rejectedAudioUrl != null) {
    audioFormats.firstOrNull { it.id == pairedAudioFormatId && it.directUrl != rejectedAudioUrl }
        ?: audioFormats.firstOrNull { it.directUrl != rejectedAudioUrl }
        ?: audioFormats.firstOrNull { it.id == pairedAudioFormatId }
        ?: audioFormats.firstOrNull()
} else {
    audioFormats.firstOrNull { it.id == pairedAudioFormatId }
        ?: audioFormats.firstOrNull()
}
