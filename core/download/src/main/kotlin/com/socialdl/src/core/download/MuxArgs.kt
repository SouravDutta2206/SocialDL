package com.socialdl.src.core.download

import com.socialdl.src.core.model.MuxStrategy
import java.io.File
import java.util.Locale

/** Builds the FFmpeg argument list for a video+audio mux. Pure: no Android or Room types. */
internal object MuxArgs {

    fun build(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        container: String,
        muxStrategy: MuxStrategy? = null,
    ): List<String> {
        // -nostdin prevents ffmpeg from consuming stdin. The explicit -map picks the first
        // video of input 0 and the first audio of input 1, so an extra track cannot be muxed
        // by accident.
        val inputs = listOf(
            "-y",
            "-nostdin",
            "-i", videoFile.absolutePath,
            "-i", audioFile.absolutePath,
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-shortest"
        )
        val codecArgs = when (container.uppercase(Locale.ROOT)) {
            "MP4", "MOV" -> when (muxStrategy) {
                MuxStrategy.REMUX_COPY -> listOf("-c", "copy", "-movflags", "+faststart")
                MuxStrategy.FORCE_H264 -> listOf(
                    "-c:v", "libx264",
                    "-preset", "fast",
                    "-crf", "23",
                    "-c:a", "aac",
                    "-b:a", "192k",
                    "-movflags", "+faststart"
                )
                MuxStrategy.TRANSCODE_AUDIO_ONLY, null -> listOf(
                    "-c:v", "copy",
                    "-c:a", "aac",
                    "-b:a", "192k",
                    "-movflags", "+faststart"
                )
            }
            "WEBM" -> listOf("-c:v", "copy", "-c:a", "libopus", "-b:a", "160k")
            // MKV (and the defensive fallback) copies any codec pair losslessly.
            else -> listOf("-c", "copy")
        }
        return inputs + codecArgs + outputFile.absolutePath
    }
}
