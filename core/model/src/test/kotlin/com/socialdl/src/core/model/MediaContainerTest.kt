package com.socialdl.src.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaContainerTest {

    private fun format(
        kind: FormatKind = FormatKind.VIDEO,
        container: String = "MP4",
        codec: String? = null,
        requiresMuxing: Boolean = false,
    ) = MediaFormat(
        id = "test",
        kind = kind,
        container = container,
        label = "test",
        codec = codec,
        requiresMuxing = requiresMuxing,
    )

    @Test
    fun `single stream only offers its source container`() {
        val webm = format(kind = FormatKind.AUDIO, container = "WEBM", codec = "opus")

        assertEquals(listOf(MediaContainer.WEBM), MediaContainer.optionsFor(webm))
        assertTrue(MediaContainer.isCompatible(webm, "webm"))
        assertFalse(MediaContainer.isCompatible(webm, "mp3"))
    }

    @Test
    fun `muxed h264 recommends mp4 and allows mkv`() {
        val h264 = format(container = "MP4", codec = "avc1.640028", requiresMuxing = true)

        assertEquals(MediaContainer.MP4, MediaContainer.recommendedFor(h264))
        assertEquals(
            listOf(MediaContainer.MP4, MediaContainer.MKV),
            MediaContainer.optionsFor(h264)
        )
        assertTrue(MediaContainer.isCompatible(h264, "mp4"))
        assertFalse(MediaContainer.isCompatible(h264, "webm"))
    }

    @Test
    fun `muxed vp9 recommends webm and never offers mp4`() {
        val vp9 = format(container = "WEBM", codec = "vp9", requiresMuxing = true)

        assertEquals(MediaContainer.WEBM, MediaContainer.recommendedFor(vp9))
        assertEquals(
            listOf(MediaContainer.WEBM, MediaContainer.MKV),
            MediaContainer.optionsFor(vp9)
        )
        assertFalse(MediaContainer.isCompatible(vp9, "mp4"))
    }

    @Test
    fun `unknown muxed codec falls back to mkv copy`() {
        val unknown = format(container = "MP4", codec = null, requiresMuxing = true)

        assertEquals(MediaContainer.MKV, MediaContainer.recommendedFor(unknown))
        assertEquals(listOf(MediaContainer.MKV), MediaContainer.optionsFor(unknown))
    }

    @Test
    fun `webm audio gets an audio mime type`() {
        assertEquals("audio/webm", MediaContainer.mimeTypeFor("WEBM", FormatKind.AUDIO))
        assertEquals("video/webm", MediaContainer.mimeTypeFor("WEBM", FormatKind.VIDEO))
        assertEquals("audio/mp4", MediaContainer.mimeTypeFor("M4A", FormatKind.AUDIO))
        assertEquals("image/webp", MediaContainer.mimeTypeFor("WEBP", FormatKind.IMAGE))
    }

    @Test
    fun `url extension is used when it is a known container`() {
        assertEquals(
            MediaContainer.WEBP,
            MediaContainer.fromUrl("https://cdn.example/photo.webp?size=large", MediaContainer.JPEG)
        )
        assertEquals(
            MediaContainer.JPEG,
            MediaContainer.fromUrl("https://cdn.example/no-extension", MediaContainer.JPEG)
        )
    }

    @Test
    fun `jpeg alias canonicalizes to jpg`() {
        assertEquals(MediaContainer.JPEG, MediaContainer.from("JPEG"))
        assertEquals("jpg", MediaContainer.extensionOf("jpeg"))
    }
}
