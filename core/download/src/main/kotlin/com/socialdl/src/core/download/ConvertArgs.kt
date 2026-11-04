package com.socialdl.src.core.download

import android.util.Base64
import com.socialdl.src.core.model.MediaContainer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the FFmpeg argument list for one conversion: audio-only, or video (either a
 * progressive file or the mux of two downloaded streams).
 *
 * Copy-first: a stream is copied whenever its codec is valid in the target container
 * and re-encoded silently only when it is not. Both entry points therefore share
 * [videoArgs] / [audioArgs], so the matrix lives in exactly one place.
 *
 * Pure apart from [pictureBlock]'s Base64 encode: no `Process`, no Android context,
 * no room for the caller to assemble argv by hand.
 */
internal object ConvertArgs {

    /**
     * Audio-only conversion (the audio pipeline).
     *
     * @param source the downloaded file (bytes as the CDN served them).
     * @param target container to produce; decides codec args, muxer, and art support.
     * @param sourceCodec codec read from [source] by the probe; a match means copy.
     * @param bitrateKbps measured source bitrate; drives `-b:a` for lossy targets.
     * @param cover normalised JPEG for attached-picture containers, or null.
     * @param pictureBlock Base64 OGG picture payload for [MediaContainer.OPUS], or null.
     */
    fun build(
        source: File,
        output: File,
        target: MediaContainer,
        sourceCodec: String?,
        bitrateKbps: Int?,
        title: String?,
        artist: String?,
        cover: File?,
        pictureBlock: String?,
    ): List<String> {
        // OPUS takes its cover as metadata, WAV cannot carry one, and the rest take it
        // as an attached picture stream.
        val picture = cover?.takeIf { MediaContainer.supportsAttachedPicture(target) }
        return buildList {
            addAll(listOf("-y", "-nostdin", "-i", source.absolutePath))
            if (picture != null) addAll(listOf("-i", picture.absolutePath))
            addAll(listOf("-map", "0:a:0"))
            if (picture != null) {
                addAll(listOf("-map", "1:v:0", "-c:v", "copy", "-disposition:v:0", "attached_pic"))
            }
            addAll(audioArgs(target, sourceCodec, bitrateKbps))
            if (target == MediaContainer.OPUS && !pictureBlock.isNullOrBlank()) {
                addAll(listOf("-metadata", "METADATA_BLOCK_PICTURE=$pictureBlock"))
            }
            addAll(outputFlags(target, title, artist))
            add(output.absolutePath)
        }
    }

    /**
     * Progressive video conversion: one input, video plus its audio track when it has
     * one. Streams are copied when they fit the target container.
     */
    fun buildVideo(
        source: File,
        output: File,
        target: MediaContainer,
        videoCodec: String?,
        audioCodec: String?,
        audioBitrateKbps: Int?,
        title: String?,
        artist: String?,
    ): List<String> = buildList {
        addAll(listOf("-y", "-nostdin", "-i", source.absolutePath))
        addAll(listOf("-map", "0:v:0"))
        if (audioCodec != null) addAll(listOf("-map", "0:a:0"))
        addAll(videoArgs(target, videoCodec))
        if (audioCodec != null) addAll(audioArgs(target, audioCodec, audioBitrateKbps))
        addAll(outputFlags(target, title, artist))
        add(output.absolutePath)
    }

    /**
     * Video codec arguments. Matroska copies anything; the other containers copy only
     * the codecs they actually support (device-verified, see
     * [MediaContainer.videoCopyable]) and otherwise re-encode silently.
     *
     * @param forceEncode used by the user's "force H.264" setting for HDR-excluded muxes.
     */
    fun videoArgs(
        target: MediaContainer,
        sourceCodec: String?,
        forceEncode: Boolean = false,
    ): List<String> {
        if (!forceEncode && (target == MediaContainer.MKV || MediaContainer.videoCopyable(target, sourceCodec))) {
            return listOf("-c:v", "copy")
        }
        // H.264 at CRF 18 is visually lossless for a typical download. VP9 runs in
        // realtime mode: the default "good" deadline encoded 1080p at 0.16x realtime on
        // the Pixel 7a (25 min for a 4-minute video), realtime+cpu-used 8 gives ~2.6x.
        return when (target) {
            MediaContainer.WEBM -> listOf(
                "-c:v", "libvpx-vp9", "-crf", "30", "-b:v", "0",
                "-deadline", "realtime", "-cpu-used", "8", "-row-mt", "1"
            )
            else -> listOf("-c:v", "libx264", "-preset", "fast", "-crf", "18")
        }
    }

    /**
     * Audio codec arguments. Matroska copies anything; WebM accepts only Opus/Vorbis,
     * and the ISO-BMFF containers accept AAC/MP3/AC-3 (MOV additionally PCM/ALAC).
     */
    fun audioArgs(
        target: MediaContainer,
        sourceCodec: String?,
        bitrateKbps: Int?,
    ): List<String> {
        if (target == MediaContainer.MKV || MediaContainer.audioCopyable(target, sourceCodec)) {
            return listOf("-c:a", "copy")
        }
        val rate = bitrateKbps
            ?.coerceIn(MIN_KBPS, MAX_KBPS)
            ?.let { listOf("-b:a", "${it}k") }
            .orEmpty()
        return when (target) {
            // Opus is 48 kHz natively; constrained VBR keeps the requested rate.
            MediaContainer.OPUS, MediaContainer.WEBM -> listOf("-c:a", "libopus") + rate +
                listOf("-vbr", "constrained", "-ar", "48000")
            MediaContainer.MP3 -> listOf("-c:a", "libmp3lame") + rate
            // Lossless/uncompressed: no bitrate target. s16 avoids 24-bit bloat.
            MediaContainer.FLAC -> listOf("-c:a", "flac", "-sample_fmt", "s16")
            MediaContainer.WAV -> listOf("-c:a", "pcm_s16le")
            // M4A, MP4, MOV: the broadly playable lossy option.
            else -> listOf("-c:a", "aac") + rate
        }
    }

    /**
     * Container-level output flags. MP4/MOV need faststart for progressive playback, and
     * `use_metadata_tags` to keep the `author` key the iTunes atom table has no slot for.
     */
    fun containerFlags(target: MediaContainer, hasAuthor: Boolean): List<String> = when (target) {
        MediaContainer.MP4, MediaContainer.MOV ->
            listOf("-movflags", if (hasAuthor) MOVFLAGS_FASTSTART_METADATA else MOVFLAGS_FASTSTART)
        else -> emptyList()
    }

    /**
     * Container-level output flags shared by every conversion: MP4/MOV faststart and
     * metadata tags, MP3's ID3v2.3, M4A's iPod muxer, and the title/artist tags.
     */
    fun outputFlags(target: MediaContainer, title: String?, artist: String?): List<String> = buildList {
        addAll(containerFlags(target, hasAuthor = !artist.isNullOrBlank()))
        addAll(metadataArgs(title, artist))
        when (target) {
            // .m4a needs the iPod muxer; MP3 needs ID3v2.3 for wide player support.
            MediaContainer.M4A -> addAll(listOf("-f", "ipod"))
            MediaContainer.MP3 -> addAll(listOf("-id3v2_version", "3"))
            else -> Unit
        }
    }
    /**
     * Title/artist tags for any container. Shared by both entry points (and by
     * [MuxArgs]) so tag spelling cannot drift.
     */
    fun metadataArgs(title: String?, artist: String?): List<String> = buildList {
        title?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { addAll(listOf("-metadata", "title=$it")) }
        artist?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { addAll(listOf("-metadata", "artist=$it")) }
    }

    /**
     * FLAC PICTURE block for the Ogg `METADATA_BLOCK_PICTURE` comment, Base64-encoded.
     *
     * Width and height are 0 ("unknown"): ffmpeg and players read the real size from
     * the JPEG itself. Every integer is big-endian — a little-endian block is rejected
     * with "Could not read mimetype from an attached picture".
     */
    fun pictureBlock(jpeg: ByteArray): String {
        val mime = "image/jpeg".toByteArray(Charsets.US_ASCII)
        val block = ByteBuffer.allocate(PICTURE_HEADER_BYTES + mime.size + jpeg.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putInt(PICTURE_TYPE_FRONT_COVER)
                putInt(mime.size)
                put(mime)
                putInt(0) // description length
                putInt(0) // width: unknown
                putInt(0) // height: unknown
                putInt(24) // colour depth
                putInt(0) // palette size
                putInt(jpeg.size)
                put(jpeg)
            }
        return Base64.encodeToString(block.array(), Base64.NO_WRAP)
    }


    private const val MOVFLAGS_FASTSTART = "+faststart"
    private const val MOVFLAGS_FASTSTART_METADATA = "+faststart+use_metadata_tags"

    /** 8 ints: type, mime length, description length, width, height, depth, palette, data length. */
    private const val PICTURE_HEADER_BYTES = 32
    private const val PICTURE_TYPE_FRONT_COVER = 3
    private const val MIN_KBPS = 8
    private const val MAX_KBPS = 512
}
