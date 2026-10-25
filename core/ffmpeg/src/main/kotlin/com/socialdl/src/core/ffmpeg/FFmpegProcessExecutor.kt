package com.socialdl.src.core.ffmpeg

import com.socialdl.src.core.common.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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

    private val frameRegex = Pattern.compile("""frame=\s*(\d+)""")
    private val fpsRegex = Pattern.compile("""fps=\s*([\d\.]+)""")
    private val sizeRegex = Pattern.compile("""size=\s*(\d+)kB""")
    private val timeRegex = Pattern.compile("""time=\s*(\d{2}):(\d{2}):(\d{2}(?:\.\d+)?)""")
    private val bitrateRegex = Pattern.compile("""bitrate=\s*([\d\.]+)kbits/s""")
    private val speedRegex = Pattern.compile("""speed=\s*([\d\.]+)x""")

    suspend fun run(args: List<String>): Flow<FFmpegEvent> = callbackFlow {
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
            trySend(FFmpegEvent.Failed(-1, e.message ?: "Failed to start process"))
            close()
            return@callbackFlow
        }

        val errorBuffer = StringBuilder()
        val stdoutBuffer = StringBuilder()

        // Drain stdout so the child never blocks on a full pipe (which would hang waitFor() indefinitely).
        val stdoutJob = launch(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (stdoutBuffer.length < 4096) {
                        stdoutBuffer.appendLine(line)
                    }
                }
            } catch (e: Exception) {
                Logger.e(tag, "Error reading ffmpeg stdout", e)
            }
        }

        val stderrJob = launch(Dispatchers.IO) {
            try {
                process.errorStream.bufferedReader().forEachLine { line ->
                    if (errorBuffer.length < 4096) {
                        errorBuffer.appendLine(line)
                    }
                    val progress = parseProgress(line)
                    if (progress != null) {
                        trySend(progress)
                    }
                }
            } catch (e: Exception) {
                Logger.e(tag, "Error reading ffmpeg stderr", e)
            }
        }

        val exitCode = withContext(Dispatchers.IO) {
            try {
                process.waitFor()
            } catch (e: CancellationException) {
                Logger.d(tag, "Process wait cancelled; killing ffmpeg process")
                process.destroyForcibly()
                throw e
            } catch (e: Exception) {
                Logger.w(tag, "Process wait failed", e)
                -1
            }
        }

        try {
            stderrJob.join()
        } catch (_: Exception) {
        }
        try {
            stdoutJob.join()
        } catch (_: Exception) {
        }

        if (exitCode == 0) {
            Logger.d(tag, "ffmpeg process completed successfully")
            trySend(FFmpegEvent.Completed)
        } else {
            val lastErrors = errorBuffer.takeLast(1000).toString()
            Logger.e(tag, "ffmpeg process failed with code $exitCode: $lastErrors")
            trySend(FFmpegEvent.Failed(exitCode, lastErrors))
        }

        close()

        awaitClose {
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
