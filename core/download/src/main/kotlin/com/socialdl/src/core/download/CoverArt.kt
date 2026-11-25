package com.socialdl.src.core.download

import android.net.Uri
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.ffmpeg.FFmpegEvent
import com.socialdl.src.core.ffmpeg.FFmpegProcessExecutor
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the job's cached thumbnail into a JPEG that any target container can embed.
 *
 * Normalising once gives a single byte source for every art mechanism (MP4 `covr`,
 * ID3 APIC, FLAC PICTURE, and the Ogg `METADATA_BLOCK_PICTURE` block), and keeps
 * WebP/PNG thumbnails out of containers that only accept JPEG/PNG. Video exports can
 * reuse this unchanged.
 */
@Singleton
class CoverArt @Inject constructor(
    private val ffmpeg: FFmpegProcessExecutor,
) {
    private val tag = "CoverArt"

    /**
     * @return a normalised `cover.jpg` inside [scratchDir], or null when there is no
     *   usable thumbnail. Never throws: missing art must not fail a download.
     */
    suspend fun prepare(thumbnailUrl: String?, scratchDir: File): File? {
        val thumbnail = localThumbnail(thumbnailUrl) ?: return null
        if (!ffmpeg.isAvailable()) {
            Logger.w(tag, "FFmpeg unavailable; exporting without cover art")
            return null
        }

        val output = File(scratchDir, COVER_FILE_NAME)
        val completed = ffmpeg.run(
            args = listOf(
                "-y", "-nostdin",
                "-i", thumbnail.absolutePath,
                // Cap the height so the Ogg picture block stays well under the kernel's
                // 128 KB per-argument limit and audio files do not carry a full-size
                // thumbnail. -2 keeps the width even for the mjpeg encoder.
                "-vf", "scale=-2:360:force_original_aspect_ratio=decrease",
                "-frames:v", "1",
                "-c:v", "mjpeg",
                "-q:v", "4",
                output.absolutePath,
            ),
            timeoutMs = COVER_TIMEOUT_MS,
        ) is FFmpegEvent.Completed

        if (!completed || output.length() == 0L) {
            Logger.w(tag, "Cover normalisation failed; exporting without cover art")
            return null
        }
        return output
    }

    /** Only a local cached file can be embedded; remote or missing thumbnails yield null. */
    private fun localThumbnail(thumbnailUrl: String?): File? {
        val raw = thumbnailUrl?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val path = when {
            raw.startsWith("file:") -> runCatching { Uri.parse(raw).path }.getOrNull()
            raw.contains("://") -> null
            else -> raw
        } ?: return null
        return File(path).takeIf { it.isFile && it.length() > 0 }
    }

    private companion object {
        const val COVER_FILE_NAME = "cover.jpg"
        const val COVER_TIMEOUT_MS = 30_000L
    }
}
