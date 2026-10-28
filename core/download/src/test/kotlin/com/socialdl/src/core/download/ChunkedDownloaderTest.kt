package com.socialdl.src.core.download

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * MockWebServer-backed tests for ChunkedDownloader (R-H.1):
 * single-stream fallback, chunked Range/Content-Range math, HTTP 416 as completion,
 * server-driven retry/backoff, and stop-request behavior.
 */
class ChunkedDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var downloader: ChunkedDownloader
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = ChunkedDownloader(OkHttpClient())
        tempDir = Files.createTempDirectory("chunked").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    private fun newFile(name: String) = File(tempDir, name)

    /** Response body of `count` bytes, values = index % 256 for content verification. */
    private fun patternedBody(count: Int): ByteArray = ByteArray(count) { i -> (i % 256).toByte() }

    /** Responds to Range requests with the exact slice + a truthful Content-Range header. */
    private class RangeDispatcher(private val payload: ByteArray) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val rangeHeader = request.getHeader("Range") ?: run {
                return MockResponse().setResponseCode(200)
                    .setBody(Buffer().write(payload))
            }
            val match = Regex("bytes=(\\d+)-(\\d+)").find(rangeHeader) ?: return MockResponse().setResponseCode(500)
            val start = match.groupValues[1].toLong()
            val end = match.groupValues[2].toLong()
            if (start >= payload.size) {
                return MockResponse().setResponseCode(416)
            }
            val slice = payload.copyOfRange(start.toInt(), (end + 1).coerceAtMost(payload.size.toLong()).toInt())
            return MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-${end}/${payload.size}")
                .setBody(Buffer().write(slice))
        }
    }

    @Test
    fun download_singleStream_writesWholeBodyAndReportsProgress() {
        val payload = patternedBody(4_096)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(Buffer().write(payload))
        )

        val destination = newFile("single.bin")
        val progressSamples = mutableListOf<Pair<Long, Long?>>()

        runBlocking {
            downloader.download(
                url = server.url("/media.bin").toString(),
                destination = destination,
                chunkSizeBytes = 0L, // non-YouTube → single stream
                platform = "TWITTER",
                isStopped = { false }
            ) { written, total -> progressSamples.add(written to total) }
        }

        assertTrue(payload.contentEquals(destination.readBytes()))
        val last = progressSamples.last()
        assertEquals(payload.size.toLong(), last.first)
        assertEquals(payload.size.toLong(), last.second)
    }

    @Test
    fun download_chunked_reassemblesRangeSlicesWithContentRangeTotal() {
        val payload = patternedBody(1_000)
        server.dispatcher = RangeDispatcher(payload)

        val destination = newFile("chunked.bin")
        val progressSamples = mutableListOf<Pair<Long, Long?>>()

        runBlocking {
            downloader.download(
                url = server.url("/big.bin").toString(),
                destination = destination,
                chunkSizeBytes = 250L, // 4 chunks for 1000 bytes
                platform = "YOUTUBE",
                isStopped = { false }
            ) { written, total -> progressSamples.add(written to total) }
        }

        assertTrue(payload.contentEquals(destination.readBytes()))
        // Total must have been parsed from the Content-Range header, surfaced via progress.
        val last = progressSamples.last()
        assertEquals(1_000L, last.first)
        assertEquals(1_000L, last.second)

        // Verify monotonically increasing non-decreasing writes.
        val written = progressSamples.map { it.first }
        assertTrue(written == written.sorted())
    }

    @Test
    fun download_chunked_416WithMatchingUnsatisfiedRangeMarksComplete() {
        // An empty object: the 416 must prove it with Content-Range: bytes */0.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(416)
                    .setHeader("Content-Range", "bytes */0")
            }
        }

        val destination = newFile("four16.bin")

        runBlocking {
            downloader.download(
                url = server.url("/gotcha.bin").toString(),
                destination = destination,
                chunkSizeBytes = 250L,
                platform = "YOUTUBE",
                isStopped = { false }
            ) { _, _ -> }
        }

        // 416 at chunkStart=0 with total=0 → nothing left to fetch.
        assertTrue(destination.exists())
        assertEquals(0L, destination.length())
    }

    @Test
    fun download_chunked_416WithMatchingTotalAfterUnknownChunksMarksComplete() {
        // Unknown total on the first chunk, then the server proves EOF with a
        // 416 whose unsatisfied-range total equals the bytes already written.
        val payload = patternedBody(250)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val start = Regex("bytes=(\\d+)-").find(request.getHeader("Range") ?: "")
                    ?.groupValues?.get(1)?.toLong() ?: 0L
                return if (start == 0L) {
                    MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-249/*")
                        .setBody(Buffer().write(payload))
                } else {
                    MockResponse().setResponseCode(416)
                        .setHeader("Content-Range", "bytes */250")
                }
            }
        }

        val destination = newFile("four16-after-chunk.bin")
        runBlocking {
            downloader.download(
                url = server.url("/four16-after-chunk.bin").toString(),
                destination = destination,
                chunkSizeBytes = 250L,
                platform = "YOUTUBE",
                isStopped = { false }
            ) { _, _ -> }
        }

        assertTrue(payload.contentEquals(destination.readBytes()))
        assertEquals(250L, destination.length())
    }

    @Test
    fun download_chunked_416WithoutContentRangeFailsInsteadOfMarkingComplete() {
        // A bare 416 cannot prove the file is complete; treating it as EOF could
        // persist a silently truncated file.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(416)
            }
        }

        val destination = newFile("four16-no-header.bin")
        val failure = runCatching {
            runBlocking {
                downloader.download(
                    url = server.url("/gotcha-no-header.bin").toString(),
                    destination = destination,
                    chunkSizeBytes = 250L,
                    platform = "YOUTUBE",
                    isStopped = { false }
                ) { _, _ -> }
            }
        }.exceptionOrNull()

        assertTrue(failure is RangeNotSatisfiableException)
        assertEquals(0L, destination.length())
    }

    @Test
    fun download_chunked_416WithConflictingTotalFails() {
        // The first response leaves the total unknown; the server then claims the
        // object is only 100 bytes although 250 bytes were already downloaded.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val start = Regex("bytes=(\\d+)-").find(request.getHeader("Range") ?: "")
                    ?.groupValues?.get(1)?.toLong() ?: 0L
                return if (start == 0L) {
                    MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-249/*")
                        .setBody(Buffer().write(patternedBody(250)))
                } else {
                    MockResponse().setResponseCode(416)
                        .setHeader("Content-Range", "bytes */100")
                }
            }
        }

        val destination = newFile("four16-conflict.bin")
        val failure = runCatching {
            runBlocking {
                downloader.download(
                    url = server.url("/gotcha-conflict.bin").toString(),
                    destination = destination,
                    chunkSizeBytes = 250L,
                    platform = "YOUTUBE",
                    isStopped = { false }
                ) { _, _ -> }
            }
        }.exceptionOrNull()

        assertTrue(failure is RangeNotSatisfiableException)
        assertEquals(250L, destination.length())
    }

    @Test
    fun download_chunked_416WithResolvedTotalFailsInsteadOfLooping() {
        // First request serves a truthful Content-Range (total=1000) for the initial
        // chunk, then every subsequent range comes back 416 — previously this spun
        // forever; now it must fail instead of looping or persisting a truncated file.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rangeHeader = request.getHeader("Range") ?: return MockResponse().setResponseCode(500)
                val start = Regex("bytes=(\\d+)-").find(rangeHeader)?.groupValues?.get(1)?.toLong() ?: 0L
                return if (start == 0L) {
                    MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-249/1000")
                        .setBody(Buffer().write(patternedBody(250)))
                } else {
                    MockResponse().setResponseCode(416)
                }
            }
        }

        val destination = newFile("four16-known-total.bin")

        var failure: Throwable? = null
        try {
            runBlocking {
                downloader.download(
                    url = server.url("/truncated.bin").toString(),
                    destination = destination,
                    chunkSizeBytes = 250L,
                    platform = "YOUTUBE",
                    isStopped = { false }
                ) { _, _ -> }
            }
        } catch (e: IOException) {
            failure = e
        }

        // 416 retries are pointless; the download fails with the 416 reason.
        assertTrue(failure is IOException)
        assertTrue(failure?.message?.contains("416") == true)
    }

    @Test
    fun download_chunked_200OnResumedChunkFailsFastInsteadOfRetrying() {
        // First chunk succeeds, then the server stops honoring Range and answers
        // 200 with the full body — must fail fast (exactly 2 requests), not burn
        // MAX_RETRIES_PER_CHUNK attempts discarding full bodies.
        val payload = patternedBody(4_000)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rangeHeader = request.getHeader("Range")
                val start = rangeHeader
                    ?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toLong() ?: 0L }
                    ?: 0L
                return if (start == 0L) {
                    MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-999/4000")
                        .setBody(Buffer().write(patternedBody(1_000)))
                } else {
                    MockResponse().setResponseCode(200)
                        .setBody(Buffer().write(payload))
                }
            }
        }

        val destination = newFile("twohundred-resume.bin")

        var failure: Throwable? = null
        try {
            runBlocking {
                downloader.download(
                    url = server.url("/rangeless.bin").toString(),
                    destination = destination,
                    chunkSizeBytes = 1_000L,
                    platform = "YOUTUBE",
                    isStopped = { false }
                ) { _, _ -> }
            }
        } catch (e: IOException) {
            failure = e
        }

        assertTrue(failure is IOException)
        // Fail fast: first chunk + the offending 200, nothing more.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun download_incompleteRange200FallsBackToSingleStream() {
        val payload = patternedBody(3_072)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(Buffer().write(payload))
        )

        val destination = newFile("fallback.bin")

        runBlocking {
            downloader.download(
                url = server.url("/nosupport.bin").toString(),
                destination = destination,
                chunkSizeBytes = 1_000L, // forced chunking, but server ignores Range
                platform = "INSTAGRAM",
                isStopped = { false }
            ) { _, _ -> }
        }

        assertTrue(payload.contentEquals(destination.readBytes()))
    }

    @Test
    fun download_retriesTransientServerErrorsWithBackoff_thenSucceeds() {
        val payload = patternedBody(512)
        // Two failing attempts, then a good 206 slice.
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-511/512")
                .setBody(Buffer().write(payload))
        )

        val destination = newFile("retry.bin")

        runBlocking {
            downloader.download(
                url = server.url("/flaky.bin").toString(),
                destination = destination,
                chunkSizeBytes = 512L,
                platform = "YOUTUBE",
                isStopped = { false }
            ) { _, _ -> }
        }

        assertTrue(payload.contentEquals(destination.readBytes()))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun download_givesUpAfterMaxRetriesPerChunk_andFails() {
        for (i in 0 until 5) {
            server.enqueue(MockResponse().setResponseCode(500))
        }

        val destination = newFile("giverup.bin")

        var failure: Throwable? = null
        try {
            runBlocking {
                downloader.download(
                    url = server.url("/doomed.bin").toString(),
                    destination = destination,
                    chunkSizeBytes = 512L,
                    platform = "YOUTUBE",
                    isStopped = { false }
                ) { _, _ -> }
            }
        } catch (e: IOException) {
            failure = e
        }

        assertTrue(failure is IOException)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun download_stoppedRequestSignalsCancellationPromptly() {
        val destination = newFile("stopped.bin")
        var result: Throwable? = null
        try {
            runBlocking {
                downloader.download(
                    url = server.url("/stopped.bin").toString(),
                    destination = destination,
                    chunkSizeBytes = 0L,
                    platform = "YOUTUBE",
                    isStopped = { true }
                ) { _, _ -> }
            }
        } catch (e: Throwable) {
            result = e
        }

        assertTrue(result is kotlinx.coroutines.CancellationException)
        assertEquals("Download was stopped", result?.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun download_chunkedRejectsMismatchedContentRange() {
        // Server answers a request starting at byte 0 with a body that claims to
        // start at byte 5. Appending it would silently corrupt the file.
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 5-9/100")
                .setBody(Buffer().write(patternedBody(5)))
        )

        val destination = newFile("mismatch.bin")
        val failure = runCatching {
            runBlocking {
                downloader.download(
                    url = server.url("/mismatch.bin").toString(),
                    destination = destination,
                    chunkSizeBytes = 10L,
                    platform = "YOUTUBE",
                    isStopped = { false }
                ) { _, _ -> }
            }
        }.exceptionOrNull()

        assertTrue(failure is RangeInvalidException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun download_chunkedRejectsOverlongChunkBody() {
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-4/100")
                .setBody(Buffer().write(patternedBody(10)))
        )

        val destination = newFile("overlong.bin")
        val failure = runCatching {
            runBlocking {
                downloader.download(
                    url = server.url("/overlong.bin").toString(),
                    destination = destination,
                    chunkSizeBytes = 5L,
                    platform = "YOUTUBE",
                    isStopped = { false }
                ) { _, _ -> }
            }
        }.exceptionOrNull()

        assertTrue(failure is RangeInvalidException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun download_chunkedRejectsShortBodyAgainstContentRange() {
        // Content-Range claims 10 bytes but only 5 arrive.
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-9/100")
                .setBody(Buffer().write(patternedBody(5)))
        )

        val destination = newFile("short-body.bin")
        val failure = runCatching {
            runBlocking {
                downloader.download(
                    url = server.url("/short-body.bin").toString(),
                    destination = destination,
                    chunkSizeBytes = 10L,
                    platform = "YOUTUBE",
                    isStopped = { false }
                ) { _, _ -> }
            }
        }.exceptionOrNull()

        assertTrue(failure is RangeInvalidException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun download_chunkedRejectsShortChunkedBodyWithoutContentLength() {
        // No Content-Length: only the bytes actually read can expose the lie.
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-9/100")
                .setChunkedBody(Buffer().write(patternedBody(5)), 2)
        )

        val destination = newFile("short-chunked-body.bin")
        val failure = runCatching {
            runBlocking {
                downloader.download(
                    url = server.url("/short-chunked-body.bin").toString(),
                    destination = destination,
                    chunkSizeBytes = 10L,
                    platform = "YOUTUBE",
                    isStopped = { false }
                ) { _, _ -> }
            }
        }.exceptionOrNull()

        assertTrue(failure is RangeInvalidException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun download_chunkedRejectsBodyLongerThanClaimedRange() {
        // Server was asked for 250 bytes but claims 100 and sends 200.
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-99/1000")
                .setBody(Buffer().write(patternedBody(200)))
        )

        val destination = newFile("long-body.bin")
        val failure = runCatching {
            runBlocking {
                downloader.download(
                    url = server.url("/long-body.bin").toString(),
                    destination = destination,
                    chunkSizeBytes = 250L,
                    platform = "YOUTUBE",
                    isStopped = { false }
                ) { _, _ -> }
            }
        }.exceptionOrNull()

        assertTrue(failure is RangeInvalidException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun download_chunkedRejectsChunkedBodyLongerThanClaimedRange() {
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-4/100")
                .setChunkedBody(Buffer().write(patternedBody(10)), 2)
        )

        val destination = newFile("long-chunked-body.bin")
        val failure = runCatching {
            runBlocking {
                downloader.download(
                    url = server.url("/long-chunked-body.bin").toString(),
                    destination = destination,
                    chunkSizeBytes = 5L,
                    platform = "YOUTUBE",
                    isStopped = { false }
                ) { _, _ -> }
            }
        }.exceptionOrNull()

        assertTrue(failure is RangeInvalidException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun download_chunkedAcceptsShorterClaimedRangeAndContinuesAtActualOffset() {
        // The server caps its response to 100 bytes even though 250 were
        // requested. That is legal as long as the body matches its own claim;
        // the next request must resume at the actual end, not the requested end.
        val payload = patternedBody(1_000)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val match = Regex("bytes=(\\d+)-(\\d+)").find(request.getHeader("Range") ?: "")
                    ?: return MockResponse().setResponseCode(500)
                val start = match.groupValues[1].toLong()
                val requestedEnd = match.groupValues[2].toLong()
                val end = if (start == 0L) 99L else minOf(requestedEnd, payload.size - 1L)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                    .setBody(Buffer().write(payload.copyOfRange(start.toInt(), (end + 1).toInt())))
            }
        }

        val destination = newFile("short-claim.bin")
        runBlocking {
            downloader.download(
                url = server.url("/short-claim.bin").toString(),
                destination = destination,
                chunkSizeBytes = 250L,
                platform = "YOUTUBE",
                isStopped = { false }
            ) { _, _ -> }
        }

        assertTrue(payload.contentEquals(destination.readBytes()))
        assertEquals("bytes=0-249", server.takeRequest().getHeader("Range"))
        assertEquals("bytes=100-349", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun download_chunkedRejectsTotalChange() {
        // The first chunk pins total=1000; a later response claiming 500 means
        // the object changed and completing would persist a corrupt file.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val start = Regex("bytes=(\\d+)-").find(request.getHeader("Range") ?: "")
                    ?.groupValues?.get(1)?.toLong() ?: 0L
                return when (start) {
                    0L -> MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-249/1000")
                        .setBody(Buffer().write(patternedBody(250)))
                    250L -> MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 250-499/500")
                        .setBody(Buffer().write(patternedBody(250)))
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }

        val destination = newFile("total-change.bin")
        val failure = runCatching {
            runBlocking {
                downloader.download(
                    url = server.url("/total-change.bin").toString(),
                    destination = destination,
                    chunkSizeBytes = 250L,
                    platform = "YOUTUBE",
                    isStopped = { false }
                ) { _, _ -> }
            }
        }.exceptionOrNull()

        assertTrue(failure is RangeInvalidException)
        assertEquals(2, server.requestCount)
        assertEquals(250L, destination.length())
    }

    @Test
    fun download_chunkedAllowsUnknownTotalThenKnown() {
        val payload = patternedBody(500)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val start = Regex("bytes=(\\d+)-").find(request.getHeader("Range") ?: "")
                    ?.groupValues?.get(1)?.toLong() ?: 0L
                return when (start) {
                    0L -> MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-249/*")
                        .setBody(Buffer().write(payload.copyOfRange(0, 250)))
                    250L -> MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 250-499/500")
                        .setBody(Buffer().write(payload.copyOfRange(250, 500)))
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }

        val destination = newFile("unknown-then-known.bin")
        runBlocking {
            downloader.download(
                url = server.url("/unknown-then-known.bin").toString(),
                destination = destination,
                chunkSizeBytes = 250L,
                platform = "YOUTUBE",
                isStopped = { false }
            ) { _, _ -> }
        }

        assertTrue(payload.contentEquals(destination.readBytes()))
        assertEquals(500L, destination.length())
    }

    @Test
    fun download_chunkedAllowsKnownTotalThenUnknown() {
        val payload = patternedBody(500)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val start = Regex("bytes=(\\d+)-").find(request.getHeader("Range") ?: "")
                    ?.groupValues?.get(1)?.toLong() ?: 0L
                return when (start) {
                    0L -> MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-249/500")
                        .setBody(Buffer().write(payload.copyOfRange(0, 250)))
                    250L -> MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 250-499/*")
                        .setBody(Buffer().write(payload.copyOfRange(250, 500)))
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }

        val destination = newFile("known-then-unknown.bin")
        runBlocking {
            downloader.download(
                url = server.url("/known-then-unknown.bin").toString(),
                destination = destination,
                chunkSizeBytes = 250L,
                platform = "YOUTUBE",
                isStopped = { false }
            ) { _, _ -> }
        }

        assertTrue(payload.contentEquals(destination.readBytes()))
        assertEquals(500L, destination.length())
    }

    @Test
    fun download_chunkedRejectsRepresentationChange() {
        // The first 206 supplies an ETag; the second ranged request is answered
        // with 200 (the representation changed). Appending that body would corrupt
        // the file, so the download must fail instead.
        val payload = patternedBody(1_000)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val start = Regex("bytes=(\\d+)-").find(request.getHeader("Range") ?: "")
                    ?.groupValues?.get(1)?.toLong() ?: 0L
                return if (start == 0L) {
                    MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-249/1000")
                        .setHeader("ETag", "\"v1\"")
                        .setBody(Buffer().write(payload.copyOfRange(0, 250)))
                } else {
                    MockResponse().setResponseCode(200)
                        .setBody(Buffer().write(payload))
                }
            }
        }

        val destination = newFile("representation-change.bin")
        val failure = runCatching {
            runBlocking {
                downloader.download(
                    url = server.url("/representation-change.bin").toString(),
                    destination = destination,
                    chunkSizeBytes = 250L,
                    platform = "YOUTUBE",
                    isStopped = { false }
                ) { _, _ -> }
            }
        }.exceptionOrNull()

        assertTrue(failure is RangeNotSupportedException)
        assertEquals(250L, destination.length())
        server.takeRequest()
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-Range"))
    }

    @Test
    fun download_chunkedRejectsChangedEtagOnLater206() {
        // A proxy that ignores If-Range can still answer 206 with a new
        // representation. The returned ETag must be compared to the pinned one.
        val payload = patternedBody(1_000)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val start = Regex("bytes=(\\d+)-").find(request.getHeader("Range") ?: "")
                    ?.groupValues?.get(1)?.toLong() ?: 0L
                return when (start) {
                    0L -> MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-249/1000")
                        .setHeader("ETag", "\"v1\"")
                        .setBody(Buffer().write(payload.copyOfRange(0, 250)))
                    250L -> MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 250-499/1000")
                        .setHeader("ETag", "\"v2\"")
                        .setBody(Buffer().write(payload.copyOfRange(250, 500)))
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }

        val destination = newFile("etag-change.bin")
        val failure = runCatching {
            runBlocking {
                downloader.download(
                    url = server.url("/etag-change.bin").toString(),
                    destination = destination,
                    chunkSizeBytes = 250L,
                    platform = "YOUTUBE",
                    isStopped = { false }
                ) { _, _ -> }
            }
        }.exceptionOrNull()

        assertTrue(failure is RangeInvalidException)
        assertEquals(250L, destination.length())
        server.takeRequest()
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-Range"))
    }

    @Test
    fun download_chunkedRejectsChangedLastModifiedOnLater206() {
        val payload = patternedBody(1_000)
        val firstModified = "Wed, 21 Oct 2015 07:28:00 GMT"
        val secondModified = "Thu, 22 Oct 2015 07:28:00 GMT"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val start = Regex("bytes=(\\d+)-").find(request.getHeader("Range") ?: "")
                    ?.groupValues?.get(1)?.toLong() ?: 0L
                return when (start) {
                    0L -> MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-249/1000")
                        .setHeader("Last-Modified", firstModified)
                        .setBody(Buffer().write(payload.copyOfRange(0, 250)))
                    250L -> MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 250-499/1000")
                        .setHeader("Last-Modified", secondModified)
                        .setBody(Buffer().write(payload.copyOfRange(250, 500)))
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }

        val destination = newFile("last-modified-change.bin")
        val failure = runCatching {
            runBlocking {
                downloader.download(
                    url = server.url("/last-modified-change.bin").toString(),
                    destination = destination,
                    chunkSizeBytes = 250L,
                    platform = "YOUTUBE",
                    isStopped = { false }
                ) { _, _ -> }
            }
        }.exceptionOrNull()

        assertTrue(failure is RangeInvalidException)
        assertEquals(250L, destination.length())
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("If-Range"))
    }

    @Test
    fun download_chunkedDoesNotSendIfRangeForLastModifiedOnly() {
        // HTTP dates are weak validators by default. A Last-Modified-only
        // server must not receive If-Range; the download still succeeds via
        // range/body/total checks and Last-Modified consistency comparison.
        val payload = patternedBody(1_000)
        val lastModified = "Wed, 21 Oct 2015 07:28:00 GMT"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val start = Regex("bytes=(\\d+)-").find(request.getHeader("Range") ?: "")
                    ?.groupValues?.get(1)?.toLong() ?: 0L
                val end = minOf(start + 249L, payload.size - 1L)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                    .setHeader("Last-Modified", lastModified)
                    .setBody(Buffer().write(payload.copyOfRange(start.toInt(), (end + 1).toInt())))
            }
        }

        val destination = newFile("last-modified-only.bin")
        runBlocking {
            downloader.download(
                url = server.url("/last-modified-only.bin").toString(),
                destination = destination,
                chunkSizeBytes = 250L,
                platform = "YOUTUBE",
                isStopped = { false }
            ) { _, _ -> }
        }

        assertTrue(payload.contentEquals(destination.readBytes()))
        repeat(server.requestCount) {
            assertNull(server.takeRequest().getHeader("If-Range"))
        }
    }

    @Test
    fun download_chunkedDoesNotSendIfRangeForWeakEtagOrLastModified() {
        // A weak ETag is not an If-Range validator, and its presence must not
        // cause the Last-Modified fallback to be sent as an If-Range date.
        val payload = patternedBody(1_000)
        val lastModified = "Wed, 21 Oct 2015 07:28:00 GMT"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val start = Regex("bytes=(\\d+)-").find(request.getHeader("Range") ?: "")
                    ?.groupValues?.get(1)?.toLong() ?: 0L
                val end = minOf(start + 249L, payload.size - 1L)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                    .setHeader("ETag", "W/\"v1\"")
                    .setHeader("Last-Modified", lastModified)
                    .setBody(Buffer().write(payload.copyOfRange(start.toInt(), (end + 1).toInt())))
            }
        }

        val destination = newFile("weak-etag.bin")
        runBlocking {
            downloader.download(
                url = server.url("/weak-etag.bin").toString(),
                destination = destination,
                chunkSizeBytes = 250L,
                platform = "YOUTUBE",
                isStopped = { false }
            ) { _, _ -> }
        }

        assertTrue(payload.contentEquals(destination.readBytes()))
        repeat(server.requestCount) {
            assertNull(server.takeRequest().getHeader("If-Range"))
        }
    }

    @Test
    fun download_chunkedAllowsOmittedValidatorOnLater206() {
        // A later 206 may omit the validator; only a present-and-different value
        // is a representation change.
        val payload = patternedBody(1_000)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val start = Regex("bytes=(\\d+)-").find(request.getHeader("Range") ?: "")
                    ?.groupValues?.get(1)?.toLong() ?: 0L
                val end = minOf(start + 249L, payload.size - 1L)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                    .apply {
                        if (start == 0L) setHeader("ETag", "\"v1\"")
                    }
                    .setBody(Buffer().write(payload.copyOfRange(start.toInt(), (end + 1).toInt())))
            }
        }

        val destination = newFile("omitted-validator.bin")
        runBlocking {
            downloader.download(
                url = server.url("/omitted-validator.bin").toString(),
                destination = destination,
                chunkSizeBytes = 250L,
                platform = "YOUTUBE",
                isStopped = { false }
            ) { _, _ -> }
        }

        assertTrue(payload.contentEquals(destination.readBytes()))
    }

    @Test
    fun download_doesNotRetryPermanentHttpErrors() {
        server.enqueue(MockResponse().setResponseCode(404))

        val destination = newFile("notfound.bin")
        val failure = runCatching {
            runBlocking {
                downloader.download(
                    url = server.url("/gone.bin").toString(),
                    destination = destination,
                    chunkSizeBytes = 0L,
                    platform = "YOUTUBE",
                    isStopped = { false }
                ) { _, _ -> }
            }
        }.exceptionOrNull()

        assertTrue(failure is HttpStatusException)
        assertEquals(404, (failure as HttpStatusException).code)
        assertEquals(1, server.requestCount)
    }
}
