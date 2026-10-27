package com.socialdl.src.extraction.youtube

import com.socialdl.src.core.common.util.ThumbnailCache
import com.socialdl.src.core.cookies.CookieStore
import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MuxStrategy
import com.socialdl.src.core.model.Platform
import com.socialdl.src.core.node.NodeBinaryProvider
import com.socialdl.src.core.python.PythonEngine
import com.socialdl.src.extraction.api.ExtractionError
import com.socialdl.src.extraction.api.ExtractionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YoutubeExtractorTest {

    private class FakePythonEngine(var resultJson: String = "") : PythonEngine(Dispatchers.Unconfined) {
        /** Records args[1] (the cookieFilePath) of the last extract() invocation. */
        var lastCookieFilePath: String? = "untouched"

        override suspend fun <T> run(
            module: String,
            function: String,
            vararg args: Any?,
            timeoutMs: Long,
            redactArgs: Boolean,
            parse: (String) -> T
        ): T {
            if (function == "extract" && args.size >= 2) {
                lastCookieFilePath = args[1] as String?
            }
            return parse(resultJson)
        }
    }

    private class FakeNodeBinaryProvider : NodeBinaryProvider(null) {
        override fun binaryDir(): String = "/fake/node/bin"
    }

    private class FakeThumbnailCache : ThumbnailCache(null) {
        override suspend fun store(sourceUrl: String?): String? = sourceUrl
    }

    /** §2.6 contract: no stored cookies → the bridge receives a null cookiefile. */
    private class FakeCookieStore : CookieStore {
        var storedJar: String? = null

        override fun hasCookies(platform: Platform): Boolean = storedJar != null
        override fun lastRefreshedAt(platform: Platform): kotlinx.coroutines.flow.Flow<Long?> =
            kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
        override suspend fun save(platform: Platform, jarContent: String) { storedJar = jarContent }
        override suspend fun clear(platform: Platform) { storedJar = null }
        override suspend fun importNetscapeFile(
            platform: Platform,
            uri: android.net.Uri
        ): CookieStore.ImportOutcome =
            CookieStore.ImportOutcome.Rejected("not implemented in fake")

        override suspend fun <T> withCookieFile(
            platform: Platform,
            block: suspend (String?) -> T
        ): T = block(storedJar)
    }

    private lateinit var fakeCookieStore: FakeCookieStore
    private lateinit var fakePythonEngine: FakePythonEngine
    private lateinit var extractor: YoutubeExtractor

    @Before
    fun setUp() {
        fakePythonEngine = FakePythonEngine()
        fakeCookieStore = FakeCookieStore()
        extractor = YoutubeExtractor(
            fakePythonEngine,
            FakeNodeBinaryProvider(),
            FakeThumbnailCache(),
            fakeCookieStore
        )
    }

    @Test
    fun supports_acceptsValidYouTubeUrls() {
        assertTrue(extractor.supports("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(extractor.supports("https://youtu.be/dQw4w9WgXcQ"))
        assertTrue(extractor.supports("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(extractor.supports("https://www.youtube.com/shorts/12345678901"))
        assertFalse(extractor.supports("https://www.instagram.com/p/12345/"))
        assertFalse(extractor.supports("https://x.com/user/status/12345"))
    }

    @Test
    fun parseYtDlpJson_parsesProgressiveAndSeparateFormatsCorrectly() = runTest {
        val sampleJson = """
        {
            "title": "Never Gonna Give You Up",
            "uploader": "Rick Astley",
            "duration": 212,
            "thumbnail_url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
            "formats": [
                {
                    "format_id": "18",
                    "ext": "mp4",
                    "vcodec": "avc1.42001E",
                    "acodec": "mp4a.40.2",
                    "width": 640,
                    "height": 360,
                    "fps": 30,
                    "filesize": 15000000,
                    "url": "https://googlevideo.com/18"
                },
                {
                    "format_id": "140",
                    "ext": "m4a",
                    "vcodec": "none",
                    "acodec": "mp4a.40.2",
                    "abr": 128.0,
                    "asr": 44100,
                    "audio_channels": 2,
                    "filesize": 3500000,
                    "url": "https://googlevideo.com/140"
                },
                {
                    "format_id": "251",
                    "ext": "webm",
                    "vcodec": "none",
                    "acodec": "opus",
                    "abr": 160.0,
                    "asr": 48000,
                    "audio_channels": 2,
                    "filesize": 4200000,
                    "url": "https://googlevideo.com/251"
                },
                {
                    "format_id": "137",
                    "ext": "mp4",
                    "vcodec": "avc1.640028",
                    "acodec": "none",
                    "width": 1920,
                    "height": 1080,
                    "fps": 30,
                    "filesize": 50000000,
                    "dynamic_range": "SDR",
                    "url": "https://googlevideo.com/137"
                },
                {
                    "format_id": "248",
                    "ext": "webm",
                    "vcodec": "vp9",
                    "acodec": "none",
                    "width": 1920,
                    "height": 1080,
                    "fps": 60,
                    "filesize": 65000000,
                    "dynamic_range": "HDR10",
                    "url": "https://googlevideo.com/248"
                }
            ]
        }
        """.trimIndent()

        val result = extractor.parseYtDlpJson(sampleJson, "https://youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue(result is ExtractionResult.Success)

        val item = (result as ExtractionResult.Success).items.first()
        assertEquals("Never Gonna Give You Up", item.title)
        assertEquals("Rick Astley", item.author)
        assertEquals(212, item.durationSeconds)
        assertEquals(Platform.YOUTUBE, item.platform)

        // Audio formats: should contain format 251 and 140, sorted descending by size
        assertEquals(2, item.audioFormats.size)
        assertEquals("251", item.audioFormats[0].id)
        assertEquals("140", item.audioFormats[1].id)
        assertFalse(item.audioFormats[0].requiresMuxing)

        // Video formats: progressive (18), video-only H264 (137), video-only VP9 (248)
        assertEquals(3, item.videoFormats.size)

        // Check progressive format (18)
        val format18 = item.videoFormats.first { it.id == "18" }
        assertFalse(format18.requiresMuxing)
        assertEquals(FormatKind.VIDEO, format18.kind)

        // Check H264 video-only format (137)
        val format137 = item.videoFormats.first { it.id == "137" }
        assertTrue(format137.requiresMuxing)
        assertEquals("251", format137.pairedAudioFormatId) // paired with best audio (251)
        assertFalse(format137.isHdr)

        // Check VP9 HDR video-only format (248)
        val format248 = item.videoFormats.first { it.id == "248" }
        assertTrue(format248.requiresMuxing)
        assertTrue(format248.isHdr)
        assertEquals(MuxStrategy.TRANSCODE_AUDIO_ONLY, format248.muxStrategy)
    }

    @Test
    fun parseYtDlpJson_detectsAuthWallEnvelope() = runTest {
        val authWallJson = """
        {
            "error": "auth_wall",
            "detail": "Sign in to confirm your age. This video may be inappropriate for some users."
        }
        """.trimIndent()

        val result = extractor.parseYtDlpJson(authWallJson, "https://youtube.com/watch?v=restricted")
        assertTrue(result is ExtractionResult.Error)
        val error = (result as ExtractionResult.Error).cause
        assertTrue(error is ExtractionError.AuthWallDetected)
        assertEquals(Platform.YOUTUBE, (error as ExtractionError.AuthWallDetected).platform)
    }

    @Test
    fun parseYtDlpJson_estimatesSizeWhenFilesizeNull() = runTest {
        val jsonWithSizeFallback = """
        {
            "title": "Sample",
            "uploader": "Channel",
            "duration": 100,
            "formats": [
                {
                    "format_id": "22",
                    "ext": "mp4",
                    "vcodec": "h264",
                    "acodec": "aac",
                    "height": 720,
                    "tbr": 2000.0,
                    "url": "https://googlevideo.com/22"
                }
            ]
        }
        """.trimIndent()

        val result = extractor.parseYtDlpJson(jsonWithSizeFallback, "https://youtube.com/watch?v=123")
        assertTrue(result is ExtractionResult.Success)
        val item = (result as ExtractionResult.Success).items.first()
        val format = item.videoFormats.first()

        // 2000 kbps * 1000 / 8 * 100 s = 25,000,000 bytes
        assertNotNull(format.approxSizeBytes)
        assertEquals(25000000L, format.approxSizeBytes)
        assertTrue(format.isSizeEstimated)
    }

    @Test
    fun extract_endToEndWithFakePythonReturnsSuccess() = runTest {
        fakePythonEngine.resultJson = """
        {
            "title": "End-to-End Test Video",
            "uploader": "Test Channel",
            "duration": 60,
            "thumbnail_url": "https://example.com/thumb.jpg",
            "formats": [
                {
                    "format_id": "18",
                    "ext": "mp4",
                    "vcodec": "avc1.42001E",
                    "acodec": "mp4a.40.2",
                    "height": 360,
                    "filesize": 5000000,
                    "url": "https://example.com/video.mp4"
                }
            ]
        }
        """.trimIndent()

        val result = extractor.extract("https://www.youtube.com/watch?v=test1234567")
        assertTrue(result is ExtractionResult.Success)
        val items = (result as ExtractionResult.Success).items
        assertEquals(1, items.size)
        assertEquals("End-to-End Test Video", items[0].title)
    }

    @Test
    fun extract_passesStoredCookiePathThrough() = runTest {
        fakeCookieStore.storedJar =
            "# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t4102444800\tSID\ttok"
        fakePythonEngine.resultJson = """
            {
                "title": "End-to-End Test Video",
                "uploader": "Test Channel",
                "duration": 60,
                "formats": []
            }
        """.trimIndent()

        extractor.extract("https://www.youtube.com/watch?v=test1234567")
        assertEquals(
            "# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t4102444800\tSID\ttok",
            fakePythonEngine.lastCookieFilePath
        )
    }

    @Test
    fun extract_passesNullCookiePathWhenUserNeverSignedIn() = runTest {
        fakePythonEngine.resultJson = """
            {
                "title": "End-to-End Test Video",
                "uploader": "Test Channel",
                "duration": 60,
                "formats": []
            }
        """.trimIndent()

        val result = extractor.extract("https://www.youtube.com/watch?v=test1234567")
        assertTrue(result is ExtractionResult.Success)
        // No new failure mode for the signed-out majority (§2.6).
        assertEquals(null, fakePythonEngine.lastCookieFilePath)
    }

    @Test
    fun parseYtDlpJson_parsesPlaylistEntriesIntoIndividualMediaItemsWithCleanUrls() = runTest {
        val playlistJson = """
        {
            "is_playlist": true,
            "title": "Monster Siren CC OST",
            "uploader": "Monster Siren Records",
            "entries": [
                {
                    "id": "hpI5vMxYJ_k",
                    "webpage_url": "https://www.youtube.com/watch?v=hpI5vMxYJ_k",
                    "title": "Operation Blade",
                    "uploader": "Monster Siren Records",
                    "duration": 239,
                    "thumbnail_url": "https://example.com/blade.jpg",
                    "formats": [
                        {
                            "format_id": "18",
                            "ext": "mp4",
                            "vcodec": "avc1.42001E",
                            "acodec": "mp4a.40.2",
                            "height": 360,
                            "filesize": 20000000,
                            "url": "https://example.com/blade_360p.mp4"
                        }
                    ]
                },
                {
                    "id": "abc123xyz89",
                    "webpage_url": "https://www.youtube.com/watch?v=abc123xyz89",
                    "title": "Operation Dawnseeker",
                    "uploader": "Monster Siren Records",
                    "duration": 261,
                    "thumbnail_url": "https://example.com/dawnseeker.jpg",
                    "formats": [
                        {
                            "format_id": "18",
                            "ext": "mp4",
                            "vcodec": "avc1.42001E",
                            "acodec": "mp4a.40.2",
                            "height": 360,
                            "filesize": 70000000,
                            "url": "https://example.com/dawnseeker_360p.mp4"
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val rawPlaylistUrl = "https://www.youtube.com/watch?v=hpI5vMxYJ_k&list=PLlod7FbJsGt2_BMNSlq99j_bYr6xAbBD0"
        val result = extractor.parseYtDlpJson(playlistJson, rawPlaylistUrl)

        assertTrue(result is ExtractionResult.Success)
        val items = (result as ExtractionResult.Success).items
        assertEquals(2, items.size)

        // Verify First Item
        assertEquals("Operation Blade", items[0].title)
        assertEquals("https://www.youtube.com/watch?v=hpI5vMxYJ_k", items[0].sourceUrl)
        assertFalse(items[0].sourceUrl.contains("&list="))
        assertEquals(1, items[0].carouselIndex)
        assertEquals(239, items[0].durationSeconds)
        assertEquals(1, items[0].videoFormats.size)

        // Verify Second Item
        assertEquals("Operation Dawnseeker", items[1].title)
        assertEquals("https://www.youtube.com/watch?v=abc123xyz89", items[1].sourceUrl)
        assertFalse(items[1].sourceUrl.contains("&list="))
        assertEquals(2, items[1].carouselIndex)
        assertEquals(261, items[1].durationSeconds)
        assertEquals(1, items[1].videoFormats.size)
    }

    @Test
    fun parseYtDlpJson_singleVideoWithPlaylistParamProducesCleanUrl() = runTest {
        val singleJson = """
        {
            "id": "hpI5vMxYJ_k",
            "title": "Operation Blade",
            "uploader": "Monster Siren Records",
            "duration": 239,
            "thumbnail_url": "https://example.com/blade.jpg",
            "formats": [
                {
                    "format_id": "18",
                    "ext": "mp4",
                    "vcodec": "avc1.42001E",
                    "acodec": "mp4a.40.2",
                    "height": 360,
                    "url": "https://example.com/video.mp4"
                }
            ]
        }
        """.trimIndent()

        val rawUrl = "https://www.youtube.com/watch?v=hpI5vMxYJ_k&list=PLlod7FbJsGt2_BMNSlq99j_bYr6xAbBD0"
        val result = extractor.parseYtDlpJson(singleJson, rawUrl)

        assertTrue(result is ExtractionResult.Success)
        val items = (result as ExtractionResult.Success).items
        assertEquals(1, items.size)
        assertEquals("https://www.youtube.com/watch?v=hpI5vMxYJ_k", items[0].sourceUrl)
        assertFalse(items[0].sourceUrl.contains("&list="))
    }

    // ---- R-H.3 parseYtDlpJson matrix (error envelopes + placeholder fallbacks) ----

    @Test
    fun parseYtDlpJson_detectsRateLimitedEnvelope() = runTest {
        val json = """
        {
            "error": "rate_limited",
            "detail": "HTTP Error 429: Too Many Requests"
        }
        """.trimIndent()

        val result = extractor.parseYtDlpJson(json, "https://youtube.com/watch?v=abc")
        assertTrue(result is ExtractionResult.Error)
        assertTrue((result as ExtractionResult.Error).cause is ExtractionError.RateLimited)
    }

    @Test
    fun parseYtDlpJson_detectsNetworkEnvelopeWithDetail() = runTest {
        val detail = "getaddrinfo failed: youtube.com not found"
        val json = """
        {
            "error": "network",
            "detail": "$detail"
        }
        """.trimIndent()

        val result = extractor.parseYtDlpJson(json, "https://youtube.com/watch?v=abc")
        assertTrue(result is ExtractionResult.Error)
        val error = (result as ExtractionResult.Error).cause
        assertTrue(error is ExtractionError.NetworkFailure)
        assertEquals(detail, (error as ExtractionError.NetworkFailure).message)
    }

    @Test
    fun parseYtDlpJson_mapsUnknownErrorToContentUnavailable() = runTest {
        val json = """
        {
            "error": "unavailable",
            "detail": "Video removed by the uploader"
        }
        """.trimIndent()

        val result = extractor.parseYtDlpJson(json, "https://youtube.com/watch?v=abc")
        assertTrue(result is ExtractionResult.Error)
        val error = (result as ExtractionResult.Error).cause
        assertTrue(error is ExtractionError.ContentUnavailable)
        assertEquals("Video removed by the uploader", (error as ExtractionError.ContentUnavailable).reason)
    }

    @Test
    fun parseYtDlpJson_audioOnlyDtoKeepsRealAudioAndPlaceholderVideo() = runTest {
        // R-J regression guard: an audio-only payload must NOT emit placeholder AUDIO
        // (that would mask the real streams); only the placeholder VIDEO gets added.
        val audioOnlyJson = """
        {
            "id": "abc123",
            "title": "Audio-only track",
            "uploader": "Some Channel",
            "duration": 100,
            "formats": [
                {
                    "format_id": "140",
                    "ext": "m4a",
                    "vcodec": "none",
                    "acodec": "mp4a.40.2",
                    "abr": 128.0,
                    "filesize": 3500000,
                    "url": "https://googlevideo.com/140"
                }
            ]
        }
        """.trimIndent()

        val result = extractor.parseYtDlpJson(audioOnlyJson, "https://youtube.com/watch?v=abc123")
        assertTrue(result is ExtractionResult.Success)
        val item = (result as ExtractionResult.Success).items.first()

        assertEquals(1, item.audioFormats.size)
        assertEquals("140", item.audioFormats[0].id)
        assertFalse(item.audioFormats[0].isPlaceholder)

        assertEquals(1, item.videoFormats.size)
        assertTrue(item.videoFormats[0].isPlaceholder)
        assertEquals("placeholder_best_video", item.videoFormats[0].id)
    }

    @Test
    fun parseYtDlpJson_noFormatsYieldsBothPlaceholders() = runTest {
        val emptyJson = """
        {
            "id": "abc123",
            "title": "Ghost video",
            "uploader": "Channel",
            "duration": 0,
            "formats": []
        }
        """.trimIndent()

        val result = extractor.parseYtDlpJson(emptyJson, "https://youtube.com/watch?v=abc123")
        assertTrue(result is ExtractionResult.Success)
        val item = (result as ExtractionResult.Success).items.first()

        assertTrue(item.videoFormats.single().isPlaceholder)
        assertTrue(item.audioFormats.single().isPlaceholder)
    }

    @Test
    fun parseYtDlpJson_malformedJsonYieldsParsingFailed() = runTest {
        val result = extractor.parseYtDlpJson("this is not json", "https://youtube.com/watch?v=abc")
        assertTrue(result is ExtractionResult.Error)
        assertTrue((result as ExtractionResult.Error).cause is ExtractionError.ParsingFailed)
    }

    @Test
    fun parseYtDlpJson_jsDiagnosticsParseWithoutBreakingEnvelopeMapping() = runTest {
        // The troubleshooting payload rides on error envelopes (and degraded
        // successes); parsing it must not disturb the normal error mapping.
        val json = """
        {
            "error": "unavailable",
            "detail": "Video unavailable",
            "js_diagnostics": {
                "runtime": null,
                "path": null,
                "version": null,
                "exec_ok": null,
                "min_supported": "22.0.0",
                "ejs_package": true,
                "warnings": ["[youtube] n challenge solving failed: Some formats may be missing."]
            }
        }
        """.trimIndent()

        val result = extractor.parseYtDlpJson(json, "https://youtube.com/watch?v=abc")
        assertTrue(result is ExtractionResult.Error)
        val error = (result as ExtractionResult.Error).cause
        assertTrue(error is ExtractionError.ContentUnavailable)
        assertEquals("Video unavailable", (error as ExtractionError.ContentUnavailable).reason)
    }

    @Test
    fun parseYtDlpJson_acceptsFloatDurationAndFps() = runTest {
        // yt-dlp emits duration/fps as JSON floats (e.g. 212.05, 29.97). The DTOs
        // once declared Int?, which threw on real-world responses. Regression:
        // float values must parse (and round) instead of failing the envelope.
        val json = """
        {
            "title": "Float Types",
            "duration": 212.05,
            "thumbnail_url": "https://i.ytimg.com/vi/abc/maxresdefault.jpg",
            "formats": [
                {
                    "format_id": "137",
                    "ext": "mp4",
                    "vcodec": "avc1.640028",
                    "acodec": "none",
                    "width": 1920,
                    "height": 1080,
                    "fps": 29.97,
                    "filesize": 50000000,
                    "dynamic_range": "SDR",
                    "url": "https://googlevideo.com/137"
                }
            ]
        }
        """.trimIndent()

        val result = extractor.parseYtDlpJson(json, "https://youtube.com/watch?v=abc")
        assertTrue(result is ExtractionResult.Success)
        val item = (result as ExtractionResult.Success).items.first()
        assertEquals(212, item.durationSeconds) // 212.05 rounds to 212
        assertEquals(30, item.videoFormats.first().fps) // 29.97 rounds to 30
    }
}
