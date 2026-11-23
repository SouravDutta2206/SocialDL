package com.socialdl.src

import android.app.Application
import android.os.Build
import com.chaquo.python.Python
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.ffmpeg.FFmpegBinaryProvider
import com.socialdl.src.core.node.NodeBinaryProvider
import com.socialdl.src.core.python.PythonBootstrap
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import javax.inject.Inject

@HiltAndroidApp
class SocialDLApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var ffmpegBinaryProvider: FFmpegBinaryProvider

    @Inject
    lateinit var nodeBinaryProvider: NodeBinaryProvider

    @Inject
    lateinit var notificationManager: com.socialdl.src.core.download.DownloadNotificationManager

    /**
     * Eagerly constructed at Application creation (Bug #1): [DownloadManager]'s
     * constructor launches startup reconciliation. Leaving this injection to the
     * UI meant reconciliation only ran after a screen was opened — by then a
     * WorkManager-started [com.socialdl.src.core.download.DownloadWorker] could
     * already be downloading, and reconciliation killed that live job. Injected
     * here so the barrier completes during `onCreate()` before any worker
     * gets a chance to read its row.
     */
    @Inject
    lateinit var downloadManager: com.socialdl.src.core.download.DownloadManager

    override val workManagerConfiguration: Configuration by lazy {
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    }

    // Guarded scope for the boot-time diagnostics: an uncaught error must never
    // take the Application down.
    private val diagnosticsScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Logger.e("SocialDL_Boot", "Diagnostics smoke test crashed", e)
        }
    )


    override fun onCreate() {
        super.onCreate()
        // Start Chaquopy on a background thread. PythonEngine awaits readiness
        // before its first call, so onCreate no longer blocks the main thread.
        PythonBootstrap.begin(this)

        // Foreground download notification channel (P1-1) — created once at app start.
        notificationManager.ensureChannel()

        // Diagnostics spawn Python and subprocesses; release cold starts must not
        // pay that cost (or log backend probes) for the whole user base.
        if (BuildConfig.DEBUG) {
            runDiagnosticsSmokeTest()
        }
        cleanupLegacyInstaloaderSessionArtifacts()
    }

    /**
     * One-time removal of artifacts left by the retired Instaloader **session**
     * flow (the anonymous Instaloader fallback used by the hybrid pipeline does
     * not use any of these):
     *
     * - the old encrypted session pickle and its cache temp directory;
     * - the old session-metadata DataStore file.
     *
     * Idempotent and silent when there is nothing to delete. The Instaloader
     * Python package itself is intentionally not touched.
     */
    private fun cleanupLegacyInstaloaderSessionArtifacts() {
        diagnosticsScope.launch {
            val targets = listOf(
                File(filesDir, "instagram_sessions"),
                File(cacheDir, "instaloader_bridge"),
                File(filesDir, "datastore/socialdl_instagram_session_meta.preferences_pb"),
            )

            var removed = 0
            for (target in targets) {
                if (!target.exists()) continue
                if (target.deleteRecursively()) removed++
            }
            if (removed > 0) {
                Logger.i("SocialDL_Boot", "Removed $removed legacy Instaloader session artifact(s)")
            }
        }
    }

    /** Timed wait with an API 24-25 fallback (Process.waitFor(timeout) is API 26+). */
    private fun waitForWithTimeout(process: Process, seconds: Long): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return process.waitFor(seconds, TimeUnit.SECONDS)
        }
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < deadline) {
            try {
                process.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(100)
            }
        }
        return false
    }

    private fun destroyProcess(process: Process) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.destroyForcibly()
        } else {
            process.destroy()
        }
    }

    /**
     * Runs one version/health subprocess: drains the entire merged output stream
     * (keeping only the first line for the log), waits with a timeout, kills the
     * process on timeout, and closes every stream in `finally`.
     *
     * Draining to EOF matters: `ffmpeg -version`/`node -e` can emit more than a
     * pipe buffer's worth of text, and reading only the first line left the child
     * blocked on a full pipe for the rest of its life.
     */
    private suspend fun runSmokeCommand(
        label: String,
        executablePath: String,
        environment: Map<String, String>,
        args: List<String>,
    ) {
        val process = ProcessBuilder(listOf(executablePath) + args)
            .redirectErrorStream(true)
            .apply { environment().putAll(environment) }
            .start()

        try {
            var firstLine = ""
            coroutineScope {
                val drain = launch(Dispatchers.IO) {
                    try {
                        process.inputStream.bufferedReader().use { reader ->
                            firstLine = reader.readLine().orEmpty()
                            // Consume the rest to EOF so the OS pipe never fills.
                            while (reader.readLine() != null) {
                                // discard
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w("SocialDL_Boot", "$label output drain failed: ${e.message}")
                    }
                }

                val finished = waitForWithTimeout(process, 10)
                if (!finished) {
                    Logger.w("SocialDL_Boot", "$label smoke test timed out after 10s; killing process")
                    destroyProcess(process)
                    runCatching { process.inputStream.close() }
                }
                // Give the drain coroutine a bounded window to reach EOF; a grandchild
                // inheriting the pipe fd must not hang boot diagnostics indefinitely.
                if (withTimeoutOrNull(1_000L) { drain.join() } == null) {
                    drain.cancel()
                }

                if (finished) {
                    val exitCode = process.exitValue()
                    Logger.i("SocialDL_Boot", "$label Smoke Test exit=$exitCode: $firstLine")
                }
            }
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { if (process.isAliveCompat()) destroyProcess(process) }
        }
    }

    /** API 26+ exposes [Process.isAlive]; older releases only exitValue(). */
    private fun Process.isAliveCompat(): Boolean = try {
        exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun runDiagnosticsSmokeTest() {
        diagnosticsScope.launch {
            try {
                // Wait for the background Chaquopy start before touching Python.
                PythonBootstrap.awaitStarted()

                // 1. Python smoke test: call common.ping per Phase 0 & Phase 2 exit criteria
                val py = Python.getInstance()
                val pingModule = py.getModule("common.ping")
                val pingResult = pingModule.callAttr("ping", "SocialDL Phase 0/2 Boot Check")
                Logger.i("SocialDL_Boot", "Chaquopy Smoke Test OK: $pingResult")

                // Pre-warm yt_dlp only in debug builds: in release it burns CPU and
                // memory on every boot for a benefit the first search already hides.
                if (BuildConfig.DEBUG) {
                    try {
                        val extractModule = py.getModule("yt_dlp_bridge.extract")
                        extractModule.callAttr("warmup")
                        Logger.i("SocialDL_Boot", "Chaquopy yt_dlp bridge pre-warmed")
                    } catch (e: Exception) {
                        Logger.w("SocialDL_Boot", "yt_dlp bridge pre-warm non-critical error: ${e.message}")
                    }
                }

                // FFmpeg/node smoke tests spawn subprocesses; only worth the boot cost in DEBUG builds.
                if (BuildConfig.DEBUG) {
                    // 2. FFmpeg binary smoke test per Phase 0 exit criteria.
                    // Resolve ONCE so the executable and its LD_LIBRARY_PATH are a
                    // matching pair even if a runtime hot-swap lands mid-check.
                    val ffmpegRuntime = ffmpegBinaryProvider.resolveRuntime()
                    val ffmpegPath = ffmpegRuntime?.executable?.absolutePath
                        ?: ffmpegBinaryProvider.path()
                    val ffmpegEnv = ffmpegRuntime?.environment
                        ?: ffmpegBinaryProvider.environment()
                    val ffmpegFile = File(ffmpegPath)
                    if (ffmpegFile.exists() && ffmpegFile.canExecute()) {
                        runSmokeCommand(
                            label = "FFmpeg",
                            executablePath = ffmpegPath,
                            environment = ffmpegEnv,
                            args = listOf("-version"),
                        )
                    } else {
                        Logger.w("SocialDL_Boot", "FFmpeg binary not executable at: $ffmpegPath")
                    }

                    // 3. Node binary smoke test per Phase 0 exit criteria.
                    val nodeRuntime = nodeBinaryProvider.resolveRuntime()
                    val nodePath = nodeRuntime?.executable?.absolutePath
                        ?: nodeBinaryProvider.path()
                    val nodeEnv = nodeRuntime?.environment
                        ?: nodeBinaryProvider.environment()
                    val nodeFile = File(nodePath)
                    if (nodeFile.exists() && nodeFile.canExecute()) {
                        runSmokeCommand(
                            label = "Node",
                            executablePath = nodePath,
                            environment = nodeEnv,
                            args = listOf("-e", "console.log('NodeJS OK')"),
                        )
                    } else {
                        Logger.w("SocialDL_Boot", "Node binary not executable at: $nodePath")
                    }
                }
            } catch (e: Exception) {
                Logger.e("SocialDL_Boot", "Diagnostics smoke test encountered an error", e)
            }
        }
    }
}

