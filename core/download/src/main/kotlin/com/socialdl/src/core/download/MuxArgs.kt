package com.socialdl.src.core.download

import com.socialdl.src.core.model.MediaContainer
import com.socialdl.src.core.model.MuxStrategy
import java.io.File

/**
 * Builds the FFmpeg argument list for a video+audio mux: two downloaded streams into
 * one file.
 *
 * The per-stream codec decisions live in [ConvertArgs] (copy when the codec fits the
 * target container, silent re-encode when it does not); this type only owns the
 * two-input mapping. Pure: no Android or Room types.
 */
internal object MuxArgs {

    fun build(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        container: String,
        videoCodec: String? = null,
        audioCodec: String? = null,
        audioBitrateKbps: Int? = null,
        muxStrategy: MuxStrategy? = null,
        title: String? = null,
        author: String? = null,
    ): List<String> {
        val target = MediaContainer.from(container) ?: MediaContainer.MP4
        // -nostdin prevents ffmpeg from consuming stdin. The explicit -map picks the first
        // video of input 0 and the first audio of input 1, so an extra track cannot be
        // muxed by accident.
        return buildList {
            addAll(
                listOf(
                    "-y", "-nostdin",
                    "-i", videoFile.absolutePath,
                    "-i", audioFile.absolutePath,
                    "-map", "0:v:0",
                    "-map", "1:a:0",
                    "-shortest",
                )
            )
            addAll(
                ConvertArgs.videoArgs(
                    target = target,
                    sourceCodec = videoCodec,
                    forceEncode = muxStrategy == MuxStrategy.FORCE_H264,
                )
            )
            addAll(ConvertArgs.audioArgs(target, audioCodec, audioBitrateKbps))
            addAll(ConvertArgs.containerFlags(target, hasAuthor = !author.isNullOrBlank()))
            addAll(ConvertArgs.metadataArgs(title, author))
            add(outputFile.absolutePath)
        }
    }
}
