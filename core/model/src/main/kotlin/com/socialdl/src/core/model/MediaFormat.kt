package com.socialdl.src.core.model

import kotlinx.serialization.Serializable

/**
 * Represents a single downloadable format/stream for a media item.
 *
 * For YouTube, a single video may have many formats (different resolutions, codecs).
 * For Instagram/Twitter/Threads, there is typically one format per media element.
 *
 * Fields per §2.3 + §4.2 of the spec.
 */
@Serializable
data class MediaFormat(
    /** Unique format ID (e.g. yt-dlp's format_id "399", or a synthetic ID). */
    val id: String,

    /** VIDEO, AUDIO, or IMAGE. */
    val kind: FormatKind,

    /** Container: MP4, WEBM, MP3, M4A, OPUS, FLAC, JPEG, etc. */
    val container: String,

    /** Human-readable quality label: "1080P (1920x1080)" / "320kbps MP3". */
    val label: String,

    /** Codec string: "AV01.0.08M.08", "H.264", "AAC-LC", etc. Null if unknown. */
    val codec: String? = null,

    /** Approximate file size in bytes. Null if completely unknown. */
    val approxSizeBytes: Long? = null,

    /** True when size was estimated from bitrate rather than reported directly. */
    val isSizeEstimated: Boolean = false,

    /** Frames per second (video only). */
    val fps: Int? = null,

    /** Video height in pixels. Typed field so ranking never parses display labels. */
    val height: Int? = null,

    /** Audio sample rate string: "48kHz", "44.1kHz". Null for video-only. */
    val sampleRate: String? = null,

    /** Number of audio channels (1=Mono, 2=Stereo). Null for video-only. */
    val audioChannels: Int? = null,

    /** True for HDR10/Dolby Vision formats. Drives mux-strategy decisions. */
    val isHdr: Boolean = false,

    /** Direct CDN URL for platforms with CDN-direct formats (Threads/Twitter/Instagram). */
    val directUrl: String? = null,

    /** True for YouTube video-only formats that need muxing with a paired audio stream. */
    val requiresMuxing: Boolean = false,

    /** For video-only YouTube formats: the best-available audio format ID to mux with. */
    val pairedAudioFormatId: String? = null,

    /** From yt-dlp's downloader_options.http_chunk_size — feeds Phase 9's chunked fetch. */
    val httpChunkSizeBytes: Long? = null,

    /**
     * Mux strategy for YouTube downloads. Determined at extraction time (Phase 4),
     * consumed at download time (Phase 9) to build the ffmpeg argument list.
     */
    val muxStrategy: MuxStrategy? = null,
) {
    val isPlaceholder: Boolean
        get() = id == PLACEHOLDER_BEST_VIDEO_ID || id == PLACEHOLDER_BEST_AUDIO_ID

    companion object {
        const val PLACEHOLDER_BEST_VIDEO_ID = "placeholder_best_video"
        const val PLACEHOLDER_BEST_AUDIO_ID = "placeholder_best_audio"

        val PLACEHOLDER_BEST_VIDEO = MediaFormat(
            id = PLACEHOLDER_BEST_VIDEO_ID,
            kind = FormatKind.VIDEO,
            container = "MP4",
            label = "BEST QUALITY",
            codec = null,
            approxSizeBytes = null,
            isSizeEstimated = false
        )

        val PLACEHOLDER_BEST_AUDIO = MediaFormat(
            id = PLACEHOLDER_BEST_AUDIO_ID,
            kind = FormatKind.AUDIO,
            container = "MP3",
            label = "BEST AUDIO",
            codec = null,
            approxSizeBytes = null,
            isSizeEstimated = false
        )
    }
}

/**
 * How ffmpeg should combine separate video + audio streams into a final .mp4 file.
 * Computed once during extraction based on codec compatibility.
 */
@Serializable
enum class MuxStrategy {
    /** Both streams are natively MP4-compatible. Fast, lossless copy. */
    REMUX_COPY,

    /** Video is copy-safe; audio needs AAC transcode (Opus-in-MP4 has poor support). */
    TRANSCODE_AUDIO_ONLY,

    /** Full H.264 re-encode. Opt-in from Settings, disabled for HDR formats. */
    FORCE_H264,
}
