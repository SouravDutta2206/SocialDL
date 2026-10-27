package com.socialdl.src.core.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FFmpegProcessExecutorTest {

    private val provider = object : FFmpegBinaryProvider(null) {
        override fun path(): String = "/fake/libffmpeg.so"
        override fun isAvailable(): Boolean = true
    }


    private val executor = FFmpegProcessExecutor(provider)

    @Test
    fun parseProgress_standardFfmpegLine() {
        val line = "frame=  120 fps= 60.5 q=-1.0 size=    2048kB time=00:01:23.45 bitrate=4194.3kbits/s speed= 2.1x"
        val progress = executor.parseProgress(line)

        assertNotNull(progress)
        assertEquals(120L, progress?.frame)
        assertEquals(60.5f, progress?.fps ?: 0f, 0.01f)
        assertEquals(2048L, progress?.sizeKb)
        // 1 min 23.45 sec = 83.45 seconds
        assertEquals(83.45, progress?.timeSeconds ?: 0.0, 0.01)
        assertEquals(4194.3f, progress?.bitrateKbps ?: 0f, 0.01f)
        assertEquals(2.1f, progress?.speed ?: 0f, 0.01f)
    }

    @Test
    fun parseProgress_partialLine() {
        val line = "frame=  450 fps= 30 size=   10240kB time=00:00:15.00 speed=1.0x"
        val progress = executor.parseProgress(line)

        assertNotNull(progress)
        assertEquals(450L, progress?.frame)
        assertEquals(30.0f, progress?.fps ?: 0f, 0.01f)
        assertEquals(10240L, progress?.sizeKb)
        assertEquals(15.0, progress?.timeSeconds ?: 0.0, 0.01)
        assertNull(progress?.bitrateKbps)
        assertEquals(1.0f, progress?.speed ?: 0f, 0.01f)
    }

    @Test
    fun parseProgress_nonProgressLineReturnsNull() {
        val line = "[libx264 @ 0x12345] using cpu capabilities: ARMv8 NEON"
        val progress = executor.parseProgress(line)
        assertNull(progress)
    }

    // ---- R-H.5 parseProgress line matrix ----

    @Test
    fun parseProgress_naBitrateIsIgnored() {
        val line = "frame=  100 fps= 25 q=-1.0 size=     512kB time=00:00:10.00 bitrate=N/A speed=1.5x"
        val progress = executor.parseProgress(line)

        assertNotNull(progress)
        assertEquals(100L, progress?.frame)
        assertEquals(25.0f, progress?.fps ?: 0f, 0.01f)
        assertEquals(512L, progress?.sizeKb)
        assertEquals(10.0, progress?.timeSeconds ?: 0.0, 0.01)
        assertNull(progress?.bitrateKbps)
        assertEquals(1.5f, progress?.speed ?: 0f, 0.01f)
    }

    @Test
    fun parseProgress_twoDigitSeconds() {
        val line = "frame=  900 fps= 30 size=   20480kB time=01:00:59.99 speed=0.25x"
        val progress = executor.parseProgress(line)

        assertNotNull(progress)
        // 1h 0m 59.99s
        assertEquals(3659.99, progress?.timeSeconds ?: 0.0, 0.01)
        assertEquals(0.25f, progress?.speed ?: 0f, 0.01f)
    }

    @Test
    fun parseProgress_routOfRange() {
        val line = "frame=    1 fps=0.75 size=     256kB time=00:00:00.04 bitrate=128.0kbits/s speed=0.5x"
        val progress = executor.parseProgress(line)

        assertNotNull(progress)
        assertEquals(0.04, progress?.timeSeconds ?: 0.0, 0.01)
    }

    @Test
    fun parseProgress_noMatchesReturnsNull() {
        assertNull(executor.parseProgress("mediasource ran out of data"))
        assertNull(executor.parseProgress(""))
    }
}
