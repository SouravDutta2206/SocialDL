package com.socialdl.src.extraction.api

import com.socialdl.src.core.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractorRegistryTest {

    private class TestExtractor(override val platform: Platform) : MediaExtractor {
        override fun supports(url: String): Boolean =
            UrlPlatformResolver.resolvePlatform(url) == platform

        override suspend fun extract(url: String): ExtractionResult =
            ExtractionResult.Success(emptyList())
    }

    private val youtubeExtractor = TestExtractor(Platform.YOUTUBE)
    private val instagramExtractor = TestExtractor(Platform.INSTAGRAM)
    private val twitterExtractor = TestExtractor(Platform.TWITTER)
    private val threadsExtractor = TestExtractor(Platform.THREADS)

    private val registry = ExtractorRegistry(
        setOf(youtubeExtractor, instagramExtractor, twitterExtractor, threadsExtractor)
    )

    @Test
    fun resolve_routesYouTubeUrl() {
        val extractor = registry.resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertNotNull(extractor)
        assertEquals(Platform.YOUTUBE, extractor?.platform)
    }

    @Test
    fun resolve_routesInstagramUrl() {
        val extractor = registry.resolve("https://www.instagram.com/p/DFxyz123abc/")
        assertNotNull(extractor)
        assertEquals(Platform.INSTAGRAM, extractor?.platform)
    }

    @Test
    fun resolve_routesTwitterUrl() {
        val extractor = registry.resolve("https://x.com/user/status/123456789")
        assertNotNull(extractor)
        assertEquals(Platform.TWITTER, extractor?.platform)
    }

    @Test
    fun resolve_routesThreadsUrl() {
        val extractor = registry.resolve("https://www.threads.net/@user/post/DFxyz123")
        assertNotNull(extractor)
        assertEquals(Platform.THREADS, extractor?.platform)
    }

    @Test
    fun resolve_returnsNullForUnknownUrl() {
        val extractor = registry.resolve("https://unknown-site.org/media/123")
        assertNull(extractor)
    }

    @Test
    fun resolve_returnsNullForBlankUrl() {
        val extractor = registry.resolve("   ")
        assertNull(extractor)
    }

    @Test
    fun allExtractors_containsAllRegistered() {
        assertEquals(4, registry.allExtractors().size)
    }
}
