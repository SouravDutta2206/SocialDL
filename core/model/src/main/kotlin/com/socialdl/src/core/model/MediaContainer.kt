package com.socialdl.src.core.model

import java.util.Locale

/**
 * A concrete output container the downloader can actually produce.
 *
 * The app never transcodes a single downloaded stream: for non-muxed formats the
 * bytes are written as-is, so the only truthful output container is the source
 * stream's container. For muxed YouTube-style downloads the container is chosen
 * from the video codec so FFmpeg never writes an invalid combination (e.g. VP9
 * copied into MP4, or H.264 copied into WebM).
 *
 * Keep this model free of Android types: it is used by extractors, UI, worker,
 * export, and unit tests.
 */
data class MediaContainer(
    /** Canonical lowercase extension used in file names (e.g. "mp4", "jpg"). */
    val extension: String,
    /** Base MIME type for the container's native kind. */
    val mimeType: String,
    /** The kind this container natively belongs to. */
    val kind: FormatKind,
) {
    /** MIME type for the container when it carries [mediaKind] media. */
    fun mimeTypeFor(mediaKind: FormatKind): String = when (mediaKind) {
        FormatKind.AUDIO -> when (extension) {
            "mp4", "m4a" -> "audio/mp4"
            "webm" -> "audio/webm"
            else -> mimeType.takeIf { it.startsWith("audio/") } ?: "audio/*"
        }
        FormatKind.IMAGE -> mimeType.takeIf { it.startsWith("image/") } ?: "image/*"
        FormatKind.VIDEO -> when (extension) {
            "mp4" -> "video/mp4"
            else -> mimeType.takeIf { it.startsWith("video/") } ?: "video/*"
        }
    }

    companion object {
        val MP4 = MediaContainer("mp4", "video/mp4", FormatKind.VIDEO)
        val MKV = MediaContainer("mkv", "video/x-matroska", FormatKind.VIDEO)
        val WEBM = MediaContainer("webm", "video/webm", FormatKind.VIDEO)
        val MOV = MediaContainer("mov", "video/quicktime", FormatKind.VIDEO)
        val M4A = MediaContainer("m4a", "audio/mp4", FormatKind.AUDIO)
        val MP3 = MediaContainer("mp3", "audio/mpeg", FormatKind.AUDIO)
        val OPUS = MediaContainer("opus", "audio/opus", FormatKind.AUDIO)
        val OGG = MediaContainer("ogg", "audio/ogg", FormatKind.AUDIO)
        val FLAC = MediaContainer("flac", "audio/flac", FormatKind.AUDIO)
        val WAV = MediaContainer("wav", "audio/wav", FormatKind.AUDIO)
        val AAC = MediaContainer("aac", "audio/aac", FormatKind.AUDIO)
        val JPEG = MediaContainer("jpg", "image/jpeg", FormatKind.IMAGE)
        val PNG = MediaContainer("png", "image/png", FormatKind.IMAGE)
        val WEBP = MediaContainer("webp", "image/webp", FormatKind.IMAGE)

        private val all = listOf(
            MP4, MKV, WEBM, MOV,
            M4A, MP3, OPUS, OGG, FLAC, WAV, AAC,
            JPEG, PNG, WEBP,
        )

        /** Historical spellings reported by extractors or older stored rows. */
        private val aliases = mapOf(
            "jpeg" to JPEG,
            "m4v" to MP4,
            "matroska" to MKV,
            "quicktime" to MOV,
        )

        private val byExtension = all.associateBy { it.extension }

        /** Parses a container name/extension; null when unknown. */
        fun from(value: String?): MediaContainer? {
            val key = value?.trim()?.removePrefix(".")?.lowercase(Locale.ROOT)
            if (key.isNullOrBlank()) return null
            return byExtension[key] ?: aliases[key]
        }

        /** Canonical extension for [value], falling back to the lowercased input. */
        fun extensionOf(value: String?): String =
            from(value)?.extension ?: value?.trim()?.trimStart('.')?.lowercase(Locale.ROOT).orEmpty()

        /**
         * The only safe output container for a stream that is downloaded as-is:
         * the stream's own container. Unknown containers fall back by kind.
         */
        fun sourceOf(format: MediaFormat): MediaContainer =
            from(format.container) ?: when (format.kind) {
                FormatKind.VIDEO -> MP4
                FormatKind.AUDIO -> M4A
                FormatKind.IMAGE -> JPEG
            }

        /**
         * Pick a container from the video codec for a muxed download so FFmpeg can
         * copy the video stream without writing an invalid combination:
         * - H.264/HEVC/AV1 → MP4 (audio is AAC or transcoded to AAC)
         * - VP8/VP9/AV1 → WebM (audio is Opus or transcoded to Opus)
         * - anything unknown → MKV, which copies any codec pair losslessly
         */
        fun recommendedFor(format: MediaFormat): MediaContainer {
            if (!format.requiresMuxing) return sourceOf(format)
            val codec = format.codec.orEmpty().lowercase(Locale.ROOT)
            return when {
                isMp4VideoCodec(codec) -> MP4
                isWebmVideoCodec(codec) -> WEBM
                else -> MKV
            }
        }

        /** Containers the picker may offer for [format]; never an invalid target. */
        fun optionsFor(format: MediaFormat): List<MediaContainer> {
            if (!format.requiresMuxing) return listOf(sourceOf(format))
            val codec = format.codec.orEmpty().lowercase(Locale.ROOT)
            val recommended = recommendedFor(format)
            return buildList {
                add(recommended)
                if (recommended != MKV) add(MKV)
                if (recommended != MP4 && isMp4VideoCodec(codec)) add(MP4)
                if (recommended != WEBM && isWebmVideoCodec(codec)) add(WEBM)
            }.distinctBy { it.extension }
        }

        /** MIME type for a raw container string, with a safe per-kind fallback. */
        fun mimeTypeFor(container: String?, kind: FormatKind): String =
            from(container)?.mimeTypeFor(kind) ?: when (kind) {
                FormatKind.AUDIO -> "audio/*"
                FormatKind.IMAGE -> "image/*"
                FormatKind.VIDEO -> "video/*"
            }

        /** True when [container] can be produced for [format] without transcoding video. */
        fun isCompatible(format: MediaFormat, container: String?): Boolean {
            val target = from(container) ?: return false
            return optionsFor(format).any { it.extension == target.extension }
        }

        /**
         * Best-effort container from a direct media URL's path extension. Used by
         * platforms whose bridge reports a URL but not a container (Instagram).
         */
        fun fromUrl(url: String?, fallback: MediaContainer): MediaContainer {
            val path = url?.substringBefore('?')?.substringBefore('#') ?: return fallback
            val extension = path.substringAfterLast('.', "")
            return from(extension) ?: fallback
        }

        private fun isMp4VideoCodec(codec: String): Boolean =
            codec.startsWith("avc") ||
                codec.startsWith("h264") ||
                codec.startsWith("hev") ||
                codec.startsWith("hvc") ||
                codec.startsWith("h265") ||
                codec.startsWith("av01") ||
                codec.startsWith("mp4v")

        private fun isWebmVideoCodec(codec: String): Boolean =
            codec.startsWith("vp8") ||
                codec.startsWith("vp9") ||
                codec.startsWith("vp0") ||
                codec.startsWith("av01")
    }
}
