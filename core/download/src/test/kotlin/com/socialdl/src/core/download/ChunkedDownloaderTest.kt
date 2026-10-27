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
    fun download_chunked_416MarksTaskCompleteInsteadOfFailing() {
        // 250-byte payload; chunk of 250 exactly covers it — then loop's range math
        // is never exercised, so instead simulate with a server that ONLY answers 416.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(416)
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

        // 416 at chunkStart=0 (totalBytes unknown) → treated as "nothing left to fetch".
        assertTrue(destination.exists())
        assertEquals(0L, destination.length())
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
