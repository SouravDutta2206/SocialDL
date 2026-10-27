package com.socialdl.src.core.common.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File

/**
 * JVM tests for ThumbnailCache (R-H.4): .tmp-aware cap eviction and the
 * per-hash lock that serializes concurrent stores of the same URL.
 */
class ThumbnailCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = temp.newFolder("cache")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newCache(): ThumbnailCache = ThumbnailCache(null).apply { useDirectoryForTest(dir) }

    private fun enqueueImage(body: ByteArray, code: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .addHeader("Content-Type", "image/jpeg")
                .setBody(Buffer().write(body))
        )
    }

    private fun bytesOf(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    @Test
    fun store_downloadsAndCachesFile() {
        enqueueImage(bytesOf("JPEGDATA"))
        val cache = newCache()
        val url = server.url("/t.jpg").toString()

        val uri = runBlocking { cache.store(url) }

        val files = dir.listFiles { _, name -> !name.endsWith(".tmp") }!!.toList()
        assertEquals(1, files.size)
        assertEquals("JPEGDATA", files[0].readText())
        assertEquals("file://${files[0].absolutePath}", uri)
    }

    @Test
    fun store_concurrentSameUrl_storesExactlyOneFile() {
        enqueueImage(bytesOf("JPEGDATA"))
        val cache = newCache()
        val url = server.url("/race.jpg").toString()

        val (a, b) = runBlocking {
            val first = async(Dispatchers.IO) { cache.store(url) }
            val second = async(Dispatchers.IO) { cache.store(url) }
            first.await() to second.await()
        }

        val files = dir.listFiles { _, name -> !name.endsWith(".tmp") }!!.toList()
        assertEquals(1, files.size)
        assertEquals(a, b)
    }

    @Test
    fun store_fallsBackToSourceUrlOnServerError() {
        enqueueImage(ByteArray(0), code = 500)
        val cache = newCache()
        val url = server.url("/oops.jpg").toString()

        val uri = runBlocking { cache.store(url) }

        assertEquals(url, uri)
        assertEquals(0, dir.listFiles()?.size)
    }

    @Test
    fun store_passthroughForNonHttpSchemes() {
        val cache = newCache()

        val fileUri = runBlocking { cache.store("file:///tmp/x.jpg") }
        assertEquals("file:///tmp/x.jpg", fileUri)
    }

    @Test
    fun enforceCacheCap_neverEvictsTempFiles() {
        val cache = newCache()
        cache.maxCacheSizeBytes = 1_000L
        // Initialize the directory once so the startup cleanup of stale .tmp files
        // (deliberately aggressive for real crash leftovers) does not remove the
        // fixture this test is about to create.
        cache.enforceCacheCap()

        // Order: .tmp is the OLDEST — it must survive because eviction skips .tmp.
        File(dir, "old.tmp").apply { writeText("t") }.setLastModified(1_000L)
        File(dir, "hash_a.jpg").apply { writeText("x".repeat(600)) }.setLastModified(2_000L)
        File(dir, "hash_b.jpg").apply { writeText("x".repeat(600)) }.setLastModified(3_000L)

        cache.enforceCacheCap()

        assertTrue(File(dir, "old.tmp").exists())
        assertTrue(File(dir, "hash_b.jpg").exists()) // newest non-tmp survives
        assertFalse(File(dir, "hash_a.jpg").exists()) // oldest non-tmp evicted
    }

    @Test
    fun enforceCacheCap_capNotReachedLeavesEverything() {
        val cache = newCache()
        val tmp = File(dir, "hash_b.tmp").apply { writeText("y") }
        File(dir, "hash_a.jpg").writeText("x".repeat(100))

        cache.enforceCacheCap()

        assertTrue(File(dir, "hash_a.jpg").exists())
        assertTrue(tmp.exists())
    }
}
