package com.socialdl.src.core.download

import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem

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
