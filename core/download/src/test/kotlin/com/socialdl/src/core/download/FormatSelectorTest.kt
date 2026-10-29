package com.socialdl.src.core.download

import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatSelectorTest {

    @Test
    fun `audio placeholder with only video formats resolves to null`() {
        val item = item(video = listOf(format("v1", FormatKind.VIDEO)))

        assertNull(FormatSelector.select(item, FormatKind.AUDIO, "placeholder_best_audio", allowExact = false))
    }

    @Test
    fun `video placeholder with only audio formats resolves to null`() {
        val item = item(audio = listOf(format("a1", FormatKind.AUDIO)))

        assertNull(FormatSelector.select(item, FormatKind.VIDEO, "placeholder_best_video", allowExact = false))
    }

    @Test
    fun `image placeholder with only video formats resolves to null`() {
        val item = item(video = listOf(format("v1", FormatKind.VIDEO)))

        assertNull(FormatSelector.select(item, FormatKind.IMAGE, null, allowExact = false))
    }

    @Test
    fun `audio request returns the first audio format`() {
        val first = format("a1", FormatKind.AUDIO)
        val item = item(
            video = listOf(format("v1", FormatKind.VIDEO)),
            audio = listOf(first, format("a2", FormatKind.AUDIO)),
        )

        assertEquals(
            first,
            FormatSelector.select(
                item,
                FormatKind.AUDIO,
                MediaFormat.PLACEHOLDER_BEST_AUDIO_ID,
                allowExact = false,
            ),
        )
    }

    @Test
    fun `video request returns the first video format`() {
        val first = format("v1", FormatKind.VIDEO)
        val item = item(
            video = listOf(first, format("v2", FormatKind.VIDEO)),
            audio = listOf(format("a1", FormatKind.AUDIO)),
        )

        assertEquals(
            first,
            FormatSelector.select(
                item,
                FormatKind.VIDEO,
                MediaFormat.PLACEHOLDER_BEST_VIDEO_ID,
                allowExact = false,
            ),
        )
    }

    @Test
    fun `image request returns the first image format`() {
        val first = format("i1", FormatKind.IMAGE)
        val item = item(
            video = listOf(format("v1", FormatKind.VIDEO)),
            image = listOf(first, format("i2", FormatKind.IMAGE)),
        )

        assertEquals(first, FormatSelector.select(item, FormatKind.IMAGE, null, allowExact = false))
    }

    @Test
    fun `exact format id of the requested kind wins`() {
        val exact = format("a2", FormatKind.AUDIO)
        val item = item(audio = listOf(format("a1", FormatKind.AUDIO), exact))

        assertEquals(
            exact,
            FormatSelector.select(item, FormatKind.AUDIO, exact.id, allowExact = true),
        )
    }

    @Test
    fun `exact format id of another kind is never returned`() {
        val mismatched = format("a1", FormatKind.AUDIO)
        val video = format("v1", FormatKind.VIDEO)
        val item = item(video = listOf(video), audio = listOf(mismatched))

        val selected = FormatSelector.select(item, FormatKind.VIDEO, mismatched.id, allowExact = true)

        assertEquals(video, selected)
    }

    @Test
    fun `exact format id of another kind with no requested kind formats resolves to null`() {
        val item = item(audio = listOf(format("a1", FormatKind.AUDIO)))

        assertNull(FormatSelector.select(item, FormatKind.VIDEO, "a1", allowExact = true))
    }

    @Test
    fun `exact format id is ignored when allowExact is false`() {
        val first = format("v1", FormatKind.VIDEO)
        val item = item(video = listOf(first, format("v2", FormatKind.VIDEO)))

        assertEquals(
            first,
            FormatSelector.select(item, FormatKind.VIDEO, "v2", allowExact = false),
        )
    }

    @Test
    fun `blank exact format id falls back to the first format of the kind`() {
        val first = format("v1", FormatKind.VIDEO)
        val item = item(video = listOf(first))

        assertEquals(first, FormatSelector.select(item, FormatKind.VIDEO, "  ", allowExact = true))
    }

    @Test
    fun `empty format lists resolve to null`() {
        val item = item()

        assertNull(FormatSelector.select(item, FormatKind.VIDEO, null, allowExact = false))
        assertNull(FormatSelector.select(item, FormatKind.AUDIO, null, allowExact = false))
        assertNull(FormatSelector.select(item, FormatKind.IMAGE, null, allowExact = false))
    }

    @Test
    fun `selection with a blank direct url is not usable`() {
        val format = format("v1", FormatKind.VIDEO, directUrl = " ")

        assertFalse(FormatSelector.isUsable(format, FormatKind.VIDEO))
    }

    @Test
    fun `selection of another kind is not usable`() {
        assertFalse(
            FormatSelector.isUsable(format("a1", FormatKind.AUDIO), FormatKind.VIDEO)
        )
    }

    @Test
    fun `null selection is not usable`() {
        assertFalse(FormatSelector.isUsable(null, FormatKind.VIDEO))
    }

    @Test
    fun `matching kind with a direct url is usable`() {
        assertTrue(
            FormatSelector.isUsable(format("v1", FormatKind.VIDEO), FormatKind.VIDEO)
        )
    }

    @Test
    fun `blank stored kind is rejected instead of defaulting to video`() {
        assertNull(FormatSelector.requestedKindOrNull(""))
        assertNull(FormatSelector.requestedKindOrNull("   "))
    }

    @Test
    fun `unrecognized stored kind is rejected instead of defaulting to video`() {
        assertNull(FormatSelector.requestedKindOrNull("GIF"))
        assertNull(FormatSelector.requestedKindOrNull("unknown"))
    }

    @Test
    fun `valid stored kind is parsed case-insensitively`() {
        assertEquals(FormatKind.AUDIO, FormatSelector.requestedKindOrNull("audio"))
        assertEquals(FormatKind.VIDEO, FormatSelector.requestedKindOrNull("VIDEO"))
        assertEquals(FormatKind.IMAGE, FormatSelector.requestedKindOrNull(" Image "))
    }

    private fun item(
        video: List<MediaFormat> = emptyList(),
        audio: List<MediaFormat> = emptyList(),
        image: List<MediaFormat> = emptyList(),
    ) = MediaItem(
        platform = Platform.INSTAGRAM,
        sourceUrl = "https://www.instagram.com/p/example/",
        title = "Example",
        videoFormats = video,
        audioFormats = audio,
        imageFormats = image,
    )

    private fun format(
        id: String,
        kind: FormatKind,
        directUrl: String? = "https://cdn.example.com/$id",
    ) = MediaFormat(
        id = id,
        kind = kind,
        container = "MP4",
        label = id,
        directUrl = directUrl,
    )
}
