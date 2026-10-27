package com.socialdl.src.core.ffmpeg

import com.socialdl.src.core.common.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ProcessBuilder-based executor for the statically-linked ffmpeg binary (§2.4).
 * Progress is parsed from stderr and cancellation forcibly terminates the process.
 */
@Singleton
class FFmpegProcessExecutor @Inject constructor(
    private val binaryProvider: FFmpegBinaryProvider
) {
    private val tag = "FFmpegExecutor"

    /** Callers can fail with a typed error before spawning a missing binary. */
    fun isAvailable(): Boolean = binaryProvider.isAvailable()

    private companion object {
        /** Large muxes can legitimately take a while; this bounds a hung process. */
        const val DEFAULT_TIMEOUT_MS = 15 * 60 * 1000L
        const val MAX_CAPTURED_CHARS = 16 * 1024
        const val MAX_ERROR_TAIL_CHARS = 4 * 1024
    }

    private val frameRegex = Pattern.compile("""frame=\s*(\d+)""")
    private val fpsRegex = Pattern.compile("""fps=\s*([\d\.]+)""")
    private val sizeRegex = Pattern.compile("""size=\s*(\d+)kB""")
    private val timeRegex = Pattern.compile("""time=\s*(\d{2}):(\d{2}):(\d{2}(?:\.\d+)?)""")
    private val bitrateRegex = Pattern.compile("""bitrate=\s*([\d\.]+)kbits/s""")
    private val speedRegex = Pattern.compile("""speed=\s*([\d\.]+)x""")

    suspend fun run(
        args: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Flow<FFmpegEvent> = callbackFlow {
        val binaryPath = binaryProvider.path()
        val command = listOf(binaryPath) + args
        Logger.d(tag, "Executing ffmpeg command: ${command.joinToString(" ")}")

        val process = try {
            val pb = ProcessBuilder(command)
                .redirectErrorStream(false)
            val env = binaryProvider.environment()
            if (env.isNotEmpty()) {
                pb.environment().putAll(env)
            }
            pb.start()
        } catch (e: Exception) {
            Logger.e(tag, "Failed to start ffmpeg process", e)
            send(FFmpegEvent.Failed(-1, e.message ?: "Failed to start process"))
            close()
            return@callbackFlow
        }

        // Terminal events (Failed/Completed) MUST reach the collector: a dropped
        // Completed makes the worker treat a successful mux as failed and delete
        // good output. send() suspends until buffer space exists instead of
        // silently dropping; progress ticks may still use trySend (droppable).
        val errorBuffer = StringBuilder()
        val stdoutBuffer = StringBuilder()

        fun appendBounded(target: StringBuilder, line: String) {
            target.appendLine(line)
            val overflow = target.length - MAX_CAPTURED_CHARS
            if (overflow > 0) target.delete(0, overflow)
        }

        // Drain stdout so the child never blocks on a full pipe (which would hang waitFor() indefinitely).
        val stdoutJob = launch(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    appendBounded(stdoutBuffer, line)
                }
            } catch (e: Exception) {
                Logger.e(tag, "Error reading ffmpeg stdout", e)
            }
        }

        val stderrJob = launch(Dispatchers.IO) {
            try {
                process.errorStream.bufferedReader().forEachLine { line ->
                    // Keep the TAIL of the log: ffmpeg reports the real failure at
                    // the end, not in the first 4 KB.
                    appendBounded(errorBuffer, line)
                    parseProgress(line)?.let { trySend(it) }
                }
            } catch (e: Exception) {
                Logger.e(tag, "Error reading ffmpeg stderr", e)
            }
        }

        var timedOut = false
        val timeoutJob = launch {
            delay(timeoutMs)
            timedOut = true
            Logger.w(tag, "ffmpeg timed out after ${timeoutMs}ms; killing process")
            process.destroyForcibly()
        }

        // runInterruptible makes waitFor() respond to coroutine cancellation (a
        // plain blocking wait ignores it). The waiter owns terminal delivery;
        // awaitClose owns cleanup, so cancellation while ffmpeg runs cannot leak
        // a process or leave the flow open.
        val waiter = launch(Dispatchers.IO) {
            val exitCode = try {
                runInterruptible { process.waitFor() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(tag, "Process wait failed", e)
                -1
            }
            timeoutJob.cancel()
            runCatching { stderrJob.join() }
            runCatching { stdoutJob.join() }

            if (exitCode == 0 && !timedOut) {
                Logger.d(tag, "ffmpeg process completed successfully")
                send(FFmpegEvent.Completed)
            } else {
                // errorBuffer already holds the bounded tail of stderr.
                val errorTail = errorBuffer.toString().takeLast(MAX_ERROR_TAIL_CHARS)
                Logger.e(tag, "ffmpeg process failed with code $exitCode: $errorTail")
                send(FFmpegEvent.Failed(if (timedOut) -2 else exitCode, errorTail))
            }
            close()
        }

        awaitClose {
            timeoutJob.cancel()
            waiter.cancel()
            stderrJob.cancel()
            stdoutJob.cancel()
            if (process.isAlive) {
                Logger.d(tag, "Forcibly killing active ffmpeg process on flow cancel")
                process.destroyForcibly()
            }
        }
    }

    /**
     * Parses tokens like "frame=  120 fps= 60 size= 2048kB time=00:00:04.00 bitrate=4194.3kbits/s speed=2.1x"
     */
    fun parseProgress(line: String): FFmpegEvent.Progress? {
        val timeMatcher = timeRegex.matcher(line)
        val frameMatcher = frameRegex.matcher(line)

        // Must at least contain time or frame to be a progress line
        if (!timeMatcher.find() && !frameMatcher.find()) {
            return null
        }

        var timeSeconds: Double? = null
        timeMatcher.reset()
        if (timeMatcher.find()) {
            val h = timeMatcher.group(1)?.toDoubleOrNull() ?: 0.0
            val m = timeMatcher.group(2)?.toDoubleOrNull() ?: 0.0
            val s = timeMatcher.group(3)?.toDoubleOrNull() ?: 0.0
            timeSeconds = h * 3600 + m * 60 + s
        }

        frameMatcher.reset()
        val frame = if (frameMatcher.find()) frameMatcher.group(1)?.toLongOrNull() else null

        val fpsMatcher = fpsRegex.matcher(line)
        val fps = if (fpsMatcher.find()) fpsMatcher.group(1)?.toFloatOrNull() else null

        val sizeMatcher = sizeRegex.matcher(line)
        val sizeKb = if (sizeMatcher.find()) sizeMatcher.group(1)?.toLongOrNull() else null

        val bitrateMatcher = bitrateRegex.matcher(line)
        val bitrateKbps = if (bitrateMatcher.find()) bitrateMatcher.group(1)?.toFloatOrNull() else null

        val speedMatcher = speedRegex.matcher(line)
        val speed = if (speedMatcher.find()) speedMatcher.group(1)?.toFloatOrNull() else null

        return FFmpegEvent.Progress(
            frame = frame,
            fps = fps,
            sizeKb = sizeKb,
            timeSeconds = timeSeconds,
            bitrateKbps = bitrateKbps,
            speed = speed
        )
    }
}
