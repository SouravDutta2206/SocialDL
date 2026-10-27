package com.socialdl.src.extraction.instagram

import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.Platform
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramMapperTest {

    private val store: suspend (String?) -> String? = { url -> url }

    @Test
    fun `single image maps to one image item without carousel index`() = runTest {
        val dto = InstagramResponseDto(
            caption = "hello",
            owner = "someone",
            items = listOf(
                InstagramItemDto(
                    index = 0,
                    media_type = "image",
                    url = "https://cdn/a.jpg",
                    thumbnail_url = "https://cdn/t.jpg",
                )
            )
        )

        val item = mapInstagramItems(dto, "https://instagram.com/p/abc", store).single()

        assertEquals(Platform.INSTAGRAM, item.platform)
        assertFalse(item.isCarousel)
        assertNull(item.carouselIndex)
        assertTrue(item.videoFormats.isEmpty())
        assertEquals(FormatKind.IMAGE, item.imageFormats.single().kind)
        // Container follows the CDN URL instead of a hardcoded JPEG label.
        assertEquals("JPG", item.imageFormats.single().container)
        assertEquals("https://cdn/a.jpg", item.imageFormats.single().directUrl)
        assertEquals("someone", item.author)
    }

    @Test
    fun `webp image keeps its real container instead of jpeg`() = runTest {
        val dto = InstagramResponseDto(
            items = listOf(
                InstagramItemDto(0, "image", "https://cdn/photo.webp?x=1", null)
            )
        )

        val format = mapInstagramItems(dto, "https://instagram.com/p/abc", store)
            .single().imageFormats.single()

        assertEquals("WEBP", format.container)
    }

    @Test
    fun `mixed carousel maps to N cards with 1-based indices`() = runTest {
        val dto = InstagramResponseDto(
            owner = "someone",
            items = listOf(
                InstagramItemDto(0, "video", "https://cdn/v.mp4", "https://cdn/v.jpg", 12.4),
                InstagramItemDto(1, "image", "https://cdn/i.jpg", "https://cdn/i.jpg"),
            )
        )

        val items = mapInstagramItems(dto, "https://instagram.com/p/abc", store)

        assertEquals(2, items.size)
        assertTrue(items.all { it.isCarousel })
        assertEquals(listOf(1, 2), items.map { it.carouselIndex })
        assertEquals(FormatKind.VIDEO, items[0].videoFormats.single().kind)
        assertFalse(items[0].videoFormats.single().requiresMuxing)
        assertEquals(12, items[0].durationSeconds)
        assertEquals(FormatKind.IMAGE, items[1].imageFormats.single().kind)
    }

    @Test
    fun `items without a direct url are dropped`() = runTest {
        val dto = InstagramResponseDto(
            items = listOf(
                InstagramItemDto(0, "image", null, null),
                InstagramItemDto(1, "image", "https://cdn/i.jpg", null),
            )
        )

        val items = mapInstagramItems(dto, "https://instagram.com/p/abc", store)

        assertEquals(1, items.size)
        assertEquals("https://cdn/i.jpg", items.single().imageFormats.single().directUrl)
    }

    @Test
    fun `blank caption falls back to a readable title`() = runTest {
        val dto = InstagramResponseDto(
            caption = "   ",
            items = listOf(InstagramItemDto(0, "image", "https://cdn/i.jpg", null))
        )

        assertEquals("Instagram Post", mapInstagramItems(dto, "url", store).single().title)
    }
}
