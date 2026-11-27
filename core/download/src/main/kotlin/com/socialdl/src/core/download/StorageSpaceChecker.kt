package com.socialdl.src.core.download

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-download free-space estimate (Folders settings: "check available storage").
 *
 * The estimate is intentionally a lower bound built from format sizes: when a
 * format's size is unknown it contributes 0 bytes, so an unknown download is
 * never blocked. A muxed video format also accounts for its paired audio stream,
 * which the worker downloads in addition to the video stream.
 *
 * The provider is a constructor seam so the unit tests need no Android framework.
 */
@Singleton
class StorageSpaceChecker internal constructor(
    private val availableBytesProvider: () -> Long,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        availableBytesProvider = { queryAvailableBytes(context) }
    )

    /**
     * Approximate bytes [format] needs, including the paired audio stream when
     * [format] requires muxing. Unknown/zero sizes count as 0.
     */
    fun estimatedDownloadBytes(item: MediaItem, format: MediaFormat): Long {
        val primary = format.approxSizeBytes?.takeIf { it > 0L } ?: 0L
        val pairedAudio = if (format.requiresMuxing) {
            selectPairedAudio(
                audioFormats = item.audioFormats,
                pairedAudioFormatId = format.pairedAudioFormatId,
            )?.approxSizeBytes?.takeIf { it > 0L } ?: 0L
        } else {
            0L
        }
        return saturatingAdd(primary, pairedAudio)
    }

    /** Sum of [estimatedDownloadBytes] over [items], saturating instead of overflowing. */
    fun totalEstimatedBytes(items: List<Pair<MediaItem, MediaFormat>>): Long =
        items.fold(0L) { total, (item, format) ->
            saturatingAdd(total, estimatedDownloadBytes(item, format))
        }

    /**
     * Throws [InsufficientStorageException] when the estimated total exceeds the
     * free space on the target volume. An all-unknown estimate never blocks.
     */
    fun ensureAvailableSpace(items: List<Pair<MediaItem, MediaFormat>>) {
        val required = totalEstimatedBytes(items)
        if (required <= 0L) return
        val available = availableBytesProvider()
        if (required > available) throw InsufficientStorageException(required, available)
    }

    private fun saturatingAdd(a: Long, b: Long): Long = when {
        b <= 0L -> a
        a > Long.MAX_VALUE - b -> Long.MAX_VALUE
        else -> a + b
    }

    private companion object {
        fun queryAvailableBytes(context: Context): Long = try {
            val storageDir = Environment.getExternalStorageDirectory() ?: context.filesDir
            StatFs(storageDir.path).availableBytes
        } catch (_: Exception) {
            // A failed StatFs query must not block downloads.
            Long.MAX_VALUE
        }
    }
}
