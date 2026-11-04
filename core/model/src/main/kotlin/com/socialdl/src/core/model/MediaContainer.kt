package com.socialdl.src.core.model

import java.util.Locale

/**
 * A concrete output container the downloader can produce.
 *
 * Non-muxed downloads are written as-is unless the user picks a different output
 * container, in which case the worker converts the bytes (see `ConvertArgs`). A
 * container whose codec matches the source codec is always a stream copy, never a
 * re-encode. For muxed YouTube-style downloads the container is chosen from the
 * video codec so FFmpeg never writes an invalid combination (e.g. VP9 copied into
 * MP4, or H.264 copied into WebM).
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
        /**
         * Ogg Opus. Uses `audio/ogg` rather than `audio/opus`: that is the type
         * MediaProvider re-derives for a `.opus` row on scan, and it resolves to more
         * players when the history entry is opened or shared.
         */
        val OPUS = MediaContainer("opus", "audio/ogg", FormatKind.AUDIO)
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

        /**
         * Output containers a non-muxed audio download can be converted to, in
         * picker order. Copy when the codec already matches, encode otherwise.
         */
        val audioTargets: List<MediaContainer> = listOf(OPUS, M4A, MP3, FLAC, WAV)

        /** Output containers a video download can be converted to, in picker order. */
        val videoTargets: List<MediaContainer> = listOf(MP4, WEBM, MOV, MKV)

        /**
         * True when [codec] can be stream-copied into [container]. Device-verified on
         * the bundled FFmpeg 7.1.1: VP9 copies into MP4 but not MOV ("vp9 only supported
         * in MP4"), AV1 likewise, and WebM accepts only VP8/VP9/AV1.
         */
        fun videoCopyable(container: MediaContainer, codec: String?): Boolean {
            val c = codec.orEmpty().lowercase(Locale.ROOT)
            if (c.isEmpty()) return false
            val h264 = c.startsWith("h264") || c.startsWith("avc")
            val hevc = c.startsWith("hevc") || c.startsWith("h265") || c.startsWith("hvc")
            val vp9 = c.startsWith("vp9")
            val av1 = c.startsWith("av1")
            return when (container) {
                MKV -> h264 || hevc || vp9 || av1
                WEBM -> vp9 || av1
                MP4 -> h264 || hevc || vp9 || av1
                MOV -> h264 || hevc
                else -> false
            }
        }

        /** True when [codec] can be stream-copied into [container] without re-encoding. */
        fun audioCopyable(container: MediaContainer, codec: String?): Boolean {
            val c = codec.orEmpty().lowercase(Locale.ROOT)
            if (c.isEmpty()) return false
            val aac = c.startsWith("aac")
            val opus = c.startsWith("opus")
            val vorbis = c.startsWith("vorbis")
            val mp3 = c.startsWith("mp3")
            val pcm = c.startsWith("pcm")
            val ac3 = c.contains("ac3")
            val flac = c.startsWith("flac")
            val alac = c.startsWith("alac")
            return when (container) {
                // Matroska accepts any codec pair losslessly.
                MKV -> true
                WEBM -> opus || vorbis
                MP4 -> aac || mp3 || ac3
                MOV -> aac || mp3 || ac3 || pcm || alac
                OPUS -> opus
                M4A -> aac
                MP3 -> mp3
                FLAC -> flac
                WAV -> pcm
                else -> false
            }
        }

        /** Codec [container] carries; null for containers this app never converts to. */
        fun codecOf(container: MediaContainer): String? = when (container) {
            OPUS -> "opus"
            M4A -> "aac"
            MP3 -> "mp3"
            FLAC -> "flac"
            WAV -> "pcm_s16le"
            else -> null
        }

        /**
         * True when [container] can carry cover art. WAV cannot: its muxer rejects any
         * video stream, so the worker skips cover preparation for it entirely.
         */
        fun supportsCoverArt(container: MediaContainer): Boolean =
            container == OPUS || supportsAttachedPicture(container)

        /**
         * Containers that take a second input as `attached_pic`. OPUS is excluded: its Ogg
         * muxer rejects a video stream, so it carries art via METADATA_BLOCK_PICTURE.
         */
        fun supportsAttachedPicture(container: MediaContainer): Boolean =
            container == M4A || container == MP3 || container == FLAC

        /** True for containers whose media can be written to MediaStore under the given kind. */
        fun isExportableMimeType(container: String?, kind: FormatKind): Boolean {
            val mime = mimeTypeFor(container, kind)
            if (mime.endsWith("/*")) return false
            // MediaProvider associates .webm with video/webm and rejects the audio collection's row.
            return !(kind == FormatKind.AUDIO && extensionOf(container) == WEBM.extension)
        }

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
            if (format.kind == FormatKind.AUDIO && !format.requiresMuxing) return nativeAudioFor(format)
            if (!format.requiresMuxing) return sourceOf(format)
            // Native = the first container that copies this codec. WEBM is tried before MP4
            // so VP9/AV1 keep their WebM home; unknown codecs fall back to MKV.
            return listOf(WEBM, MP4).firstOrNull { videoCopyable(it, format.codec) } ?: MKV
        }

        /** Containers the picker may offer for [format]; never an invalid target. */
        fun optionsFor(format: MediaFormat): List<MediaContainer> {
            if (format.kind == FormatKind.AUDIO && !format.requiresMuxing) return audioTargets
            // Video always offers the four output containers: the worker copies the
            // streams it can and silently re-encodes only the one that cannot fit.
            if (format.kind == FormatKind.VIDEO) return videoTargets
            return listOf(sourceOf(format))
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

        /**
         * Audio output that holds the source codec without re-encoding: the "Save as"
         * default, so the untouched path stays a lossless copy.
         */
        private fun nativeAudioFor(format: MediaFormat): MediaContainer {
            val codec = format.codec.orEmpty().lowercase(Locale.ROOT)
            val source = from(format.container)
            return when {
                codec.startsWith("opus") || source == WEBM -> OPUS
                codec.startsWith("aac") || codec.startsWith("mp4a") || source == M4A || source == MP4 -> M4A
                codec.startsWith("mp3") || source == MP3 -> MP3
                codec.startsWith("flac") || source == FLAC -> FLAC
                codec.startsWith("pcm") || source == WAV -> WAV
                else -> OPUS
            }
        }

    }
}
