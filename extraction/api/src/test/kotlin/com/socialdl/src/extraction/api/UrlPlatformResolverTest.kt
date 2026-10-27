package com.socialdl.src.extraction.api

import com.socialdl.src.core.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlPlatformResolverTest {

    @Test
    fun resolveYouTube_standardWatchUrl() {
        assertEquals(
            Platform.YOUTUBE,
            UrlPlatformResolver.resolvePlatform("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
    }

    @Test
    fun resolveYouTube_shortUrl() {
        assertEquals(
            Platform.YOUTUBE,
            UrlPlatformResolver.resolvePlatform("https://youtu.be/dQw4w9WgXcQ")
        )
    }

    @Test
    fun resolveYouTube_shortsUrl() {
        assertEquals(
            Platform.YOUTUBE,
            UrlPlatformResolver.resolvePlatform("https://youtube.com/shorts/abc123XYZ")
        )
    }

    @Test
    fun resolveYouTube_mobileUrl() {
        assertEquals(
            Platform.YOUTUBE,
            UrlPlatformResolver.resolvePlatform("https://m.youtube.com/watch?v=5l-r3Z_t09k")
        )
    }

    @Test
    fun resolveYouTube_schemeless() {
        assertEquals(
            Platform.YOUTUBE,
            UrlPlatformResolver.resolvePlatform("youtube.com/watch?v=5l-r3Z_t09k")
        )
    }

    @Test
    fun resolveInstagram_postUrl() {
        assertEquals(
            Platform.INSTAGRAM,
            UrlPlatformResolver.resolvePlatform("https://www.instagram.com/p/DFxyz123abc/")
        )
    }

    @Test
    fun resolveInstagram_reelUrl() {
        assertEquals(
            Platform.INSTAGRAM,
            UrlPlatformResolver.resolvePlatform("https://instagram.com/reel/C8x7yZ_123/")
        )
    }

    @Test
    fun resolveTwitter_standardStatusUrl() {
        assertEquals(
            Platform.TWITTER,
            UrlPlatformResolver.resolvePlatform("https://twitter.com/user/status/1234567890123456789")
        )
    }

    @Test
    fun resolveTwitter_xDotCom() {
        assertEquals(
            Platform.TWITTER,
            UrlPlatformResolver.resolvePlatform("https://x.com/user/status/1234567890123456789")
        )
    }

    @Test
    fun resolveTwitter_tCoShortLink() {
        assertEquals(
            Platform.TWITTER,
            UrlPlatformResolver.resolvePlatform("https://t.co/abcXYZ123")
        )
    }

    @Test
    fun resolveThreads_postUrl() {
        assertEquals(
            Platform.THREADS,
            UrlPlatformResolver.resolvePlatform("https://www.threads.net/@zuck/post/DFxyz123")
        )
    }

    @Test
    fun resolveThreads_shareUrl() {
        assertEquals(
            Platform.THREADS,
            UrlPlatformResolver.resolvePlatform("https://threads.net/share/post123")
        )
    }

    @Test
    fun resolveUnknown_invalidUrl() {
        assertEquals(
            Platform.UNKNOWN,
            UrlPlatformResolver.resolvePlatform("https://example.com/some/video")
        )
    }

    @Test
    fun resolveUnknown_emptyString() {
        assertEquals(
            Platform.UNKNOWN,
            UrlPlatformResolver.resolvePlatform("")
        )
    }

    // ---- Fix #15 / R-H matrix: new URL shapes ----

    @Test
    fun resolveYouTube_liveUrl() {
        assertEquals(
            Platform.YOUTUBE,
            UrlPlatformResolver.resolvePlatform("https://www.youtube.com/live/jfKfPfyJRdk?feature=share")
        )
    }

    @Test
    fun resolveYouTube_playlistUrl() {
        assertEquals(
            Platform.YOUTUBE,
            UrlPlatformResolver.resolvePlatform("https://www.youtube.com/playlist?list=PLlod7FbJsGt2_BMNSlq99j_bYr6xAbBD0")
        )
    }

    @Test
    fun resolveYouTube_playlistUrlSchemeless() {
        assertEquals(
            Platform.YOUTUBE,
            UrlPlatformResolver.resolvePlatform("youtube.com/playlist?list=PL123abc-_")
        )
    }

    @Test
    fun resolveYouTube_embedUrl() {
        assertEquals(
            Platform.YOUTUBE,
            UrlPlatformResolver.resolvePlatform("https://www.youtube.com/embed/jfKfPfyJRdk")
        )
    }

    @Test
    fun resolveYouTube_vUrl() {
        assertEquals(
            Platform.YOUTUBE,
            UrlPlatformResolver.resolvePlatform("https://www.youtube.com/v/dQw4w9WgXcQ")
        )
    }

    @Test
    fun resolveInstagram_reelsPluralUrl() {
        assertEquals(
            Platform.INSTAGRAM,
            UrlPlatformResolver.resolvePlatform("https://www.instagram.com/reels/C8x7yZ_123/")
        )
    }

    @Test
    fun resolveInstagram_tvUrl() {
        assertEquals(
            Platform.INSTAGRAM,
            UrlPlatformResolver.resolvePlatform("https://www.instagram.com/tv/C8x7yZ_123/")
        )
    }

    @Test
    fun resolveInstagram_storiesUrl() {
        assertEquals(
            Platform.INSTAGRAM,
            UrlPlatformResolver.resolvePlatform("https://www.instagram.com/stories/some.user/12345678/")
        )
    }

    @Test
    fun resolveTwitter_xDotComInternalWebStatusUrl() {
        assertEquals(
            Platform.TWITTER,
            UrlPlatformResolver.resolvePlatform("https://x.com/i/web/status/1718953707222096203")
        )
    }

    @Test
    fun resolveTwitter_iStatusUrl() {
        assertEquals(
            Platform.TWITTER,
            UrlPlatformResolver.resolvePlatform("https://twitter.com/i/status/1718953707222096203")
        )
    }

    @Test
    fun resolveTwitter_tCoSchemeless() {
        assertEquals(
            Platform.TWITTER,
            UrlPlatformResolver.resolvePlatform("t.co/abcXYZ123")
        )
    }

    @Test
    fun resolveYouTube_musicUrl() {
        assertEquals(
            Platform.YOUTUBE,
            UrlPlatformResolver.resolvePlatform("https://music.youtube.com/watch?v=dQw4w9WgXcQ")
        )
    }

    @Test
    fun resolveTwitter_mobileUrl() {
        assertEquals(
            Platform.TWITTER,
            UrlPlatformResolver.resolvePlatform("https://mobile.twitter.com/user/status/1234567890123456789")
        )
    }

    @Test
    fun resolveUnknown_spoofedDomainIsNotSubstringMatched() {
        // "://youtube.com" appears inside the URL, but the HOST is youtube.com.evil.com.
        assertEquals(
            Platform.UNKNOWN,
            UrlPlatformResolver.resolvePlatform("https://youtube.com.evil.com/watch?v=x")
        )
    }

    @Test
    fun resolveUnknown_redirectWrappedUrlIsNotHijacked() {
        // The real host is l.example.com; youtu.be only appears as a query param.
        assertEquals(
            Platform.UNKNOWN,
            UrlPlatformResolver.resolvePlatform("https://l.example.com/redirect?target=https://youtu.be/xyz")
        )
    }
}
