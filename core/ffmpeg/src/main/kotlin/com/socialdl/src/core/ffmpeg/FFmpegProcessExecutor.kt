package com.socialdl.src.core.ffmpeg

import android.os.Build
import com.socialdl.src.core.common.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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

        /** `ffmpeg -i` on a local file returns in milliseconds; this is only a hang guard. */
        const val PROBE_TIMEOUT_MS = 5_000L
        const val MAX_CAPTURED_CHARS = 16 * 1024
        const val MAX_ERROR_TAIL_CHARS = 4 * 1024
    }

    suspend fun run(
        args: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        /**
         * Suppresses the error-level log for a failed process. Probes exit non-zero
         * by design (`ffmpeg -i` has no output), so they must not look like errors.
         */
        quiet: Boolean = false,
        /**
         * How many trailing stderr characters [FFmpegEvent.Failed.errorLog] carries.
         * Probes need the whole (16 KB) metadata banner, not the usual error tail.
         */
        maxErrorTailChars: Int = MAX_ERROR_TAIL_CHARS,
    ): Flow<FFmpegEvent> = callbackFlow {
        // Resolve ONCE: the executable and its environment must come from the same
        // runtime snapshot, or a hot-swap between two resolve() calls can pair an
        // old binary with a new LD_LIBRARY_PATH (or vice versa).
        val runtime = binaryProvider.resolveRuntime()
        val binaryPath = runtime?.executable?.absolutePath ?: binaryProvider.path()
        val command = listOf(binaryPath) + args
        Logger.d(tag, "Executing ffmpeg command: ${command.joinToString(" ")}")

        val process = try {
            val pb = ProcessBuilder(command)
                .redirectErrorStream(false)
            val env = runtime?.environment ?: binaryProvider.environment()
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
            // stdout/stderr are drained on two IO coroutines and the waiter reads
            // errorBuffer from a third one: every access must be synchronized or
            // the StringBuilder can be corrupted (lost/garbled failure text).
            appendBoundedLine(target, line, MAX_CAPTURED_CHARS)
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
                }
            } catch (e: Exception) {
                Logger.e(tag, "Error reading ffmpeg stderr", e)
            }
        }

        val timedOut = AtomicBoolean(false)
        val timeoutJob = launch {
            delay(timeoutMs)
            timedOut.set(true)
            Logger.w(tag, "ffmpeg timed out after ${timeoutMs}ms; killing process")
            process.destroyForciblyCompat()
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
            // Never swallow cancellation: a cancelled collector must unwind with
            // CancellationException instead of reporting a bogus Failed(-1).
            try {
                stderrJob.join()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Drain failure only loses diagnostics; the exit code stands.
            }
            try {
                stdoutJob.join()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Drain failure only loses diagnostics; the exit code stands.
            }

            val didTimeOut = timedOut.get()
            if (exitCode == 0 && !didTimeOut) {
                Logger.d(tag, "ffmpeg process completed successfully")
                send(FFmpegEvent.Completed)
            } else {
                // errorBuffer already holds the bounded tail of stderr.
                val errorTail = boundedTail(errorBuffer, maxErrorTailChars)
                if (!quiet) {
                    Logger.e(tag, "ffmpeg process failed with code $exitCode: $errorTail")
                }
                send(FFmpegEvent.Failed(if (didTimeOut) -2 else exitCode, errorTail))
            }
            close()
        }

        awaitClose {
            timeoutJob.cancel()
            waiter.cancel()
            stderrJob.cancel()
            stdoutJob.cancel()
            if (process.isAliveCompat()) {
                Logger.d(tag, "Forcibly killing active ffmpeg process on flow cancel")
                process.destroyForciblyCompat()
            }
        }
    }

    /**
     * Reads the ffmpeg banner for [file]: the stream codecs, the source bitrate, and
     * the duration.
     *
     * `ffmpeg -i <file>` exits 1 by design (no output file), so this reuses [run]
     * with [quiet] logging and parses the captured stderr instead of treating the
     * exit code as a failure. Returns null when the banner has neither an audio nor a
     * video stream.
     */
    suspend fun probe(file: File): MediaProbe? {
        var banner: String? = null
        run(
            // -nostdin prevents ffmpeg from touching the app's stdin; -hide_banner
            // removes the build banner so the captured tail is metadata only.
            args = listOf("-hide_banner", "-nostdin", "-v", "info", "-i", file.absolutePath),
            timeoutMs = PROBE_TIMEOUT_MS,
            quiet = true,
            // A rich metadata/chapter block can exceed the 4 KB error tail; keep
            // the full 16 KB capture so the stream lines are never truncated away.
            maxErrorTailChars = MAX_CAPTURED_CHARS,
        ).collect { event ->
            if (event is FFmpegEvent.Failed) banner = event.errorLog
        }
        return banner?.let(::parseProbeBanner)
    }
}

/**
 * Appends [line] to [target] while keeping only the newest [maxChars] characters.
 *
 * ffmpeg's stdout and stderr are drained by two coroutines and the error tail is
 * read by the waiter coroutine, so every access is synchronized on the buffer.
 */
internal fun appendBoundedLine(target: StringBuilder, line: String, maxChars: Int) {
    synchronized(target) {
        target.appendLine(line)
        val overflow = target.length - maxChars
        if (overflow > 0) target.delete(0, overflow)
    }
}

/** Thread-safe snapshot of the last [maxChars] characters of [target]. */
internal fun boundedTail(target: StringBuilder, maxChars: Int): String =
    synchronized(target) { target.toString().takeLast(maxChars) }

/** Codecs, source bitrate, and duration read from ffmpeg's `-i` banner. */
data class MediaProbe(
    val audioCodec: String?,
    val videoCodec: String?,
    val bitrateKbps: Int?,
    val durationMs: Long?,
)

// Every pattern is anchored to a real ffmpeg structural line (Stream/Duration).
// Without MULTILINE anchoring, a Metadata value such as `title : Video: fake`
// could hijack codec detection and make a mux choose the wrong strategy.
private val AUDIO_CODEC_REGEX = Regex(
    """^\s*Stream #\d+:\d+[^\n]*?:\s*Audio:\s*([A-Za-z0-9_]+)""",
    RegexOption.MULTILINE,
)
private val VIDEO_CODEC_REGEX = Regex(
    """^\s*Stream #\d+:\d+[^\n]*?:\s*Video:\s*([A-Za-z0-9_]+)""",
    RegexOption.MULTILINE,
)
private val STREAM_BITRATE_REGEX = Regex(
    """^\s*Stream #\d+:\d+[^\n]*?:\s*Audio:[^\n]*?,\s*(\d+)\s*kb/s""",
    RegexOption.MULTILINE,
)
private val CONTAINER_BITRATE_REGEX = Regex(
    """^\s*Duration:[^\n]*?bitrate:\s*(\d+)\s*kb/s""",
    RegexOption.MULTILINE,
)
private val DURATION_REGEX = Regex(
    """^\s*Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""",
    RegexOption.MULTILINE,
)

/**
 * Parses one ffmpeg `-i` banner. Both stream kinds are optional so the same probe
 * serves an audio-only download, a muxed video-only temp, and a progressive file
 * that already contains both. Prefers the stream-level audio bitrate (present for
 * MP4/M4A) over the container bitrate.
 */
internal fun parseProbeBanner(banner: String): MediaProbe? {
    val audioCodec = AUDIO_CODEC_REGEX.find(banner)?.groupValues?.get(1)
    val videoCodec = VIDEO_CODEC_REGEX.find(banner)?.groupValues?.get(1)
    if (audioCodec == null && videoCodec == null) return null
    val streamKbps = STREAM_BITRATE_REGEX.find(banner)?.groupValues?.get(1)?.toIntOrNull()
    val containerKbps = CONTAINER_BITRATE_REGEX.find(banner)?.groupValues?.get(1)?.toIntOrNull()
    val durationMs = DURATION_REGEX.find(banner)?.let { match ->
        val hours = match.groupValues[1].toLong()
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toDouble()
        (hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).toLong()
    }
    return MediaProbe(
        audioCodec = audioCodec,
        videoCodec = videoCodec,
        bitrateKbps = streamKbps ?: containerKbps,
        durationMs = durationMs
    )
}

/**
 * [Process.destroyForcibly] is API 26+; minSdk is 24. On older releases SIGTERM
 * ([Process.destroy]) is the only signal available, which ffmpeg handles by exiting.
 */
private fun Process.destroyForciblyCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        destroyForcibly()
    } else {
        destroy()
    }
}

/** [Process.isAlive] is API 26+; older releases report liveness via [Process.exitValue]. */
private fun Process.isAliveCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        isAlive
    } else {
        try {
            exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }
