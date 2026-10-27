package com.socialdl.src

import android.app.Application
import android.os.Build
import com.chaquo.python.Python
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.ffmpeg.FFmpegBinaryProvider
import com.socialdl.src.core.node.NodeBinaryProvider
import com.socialdl.src.core.python.PythonBootstrap
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

        runDiagnosticsSmokeTest()
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
                    // 2. FFmpeg binary smoke test per Phase 0 exit criteria
                    val ffmpegPath = ffmpegBinaryProvider.path()
                    val ffmpegFile = File(ffmpegPath)
                    val ldPath = ffmpegBinaryProvider.librarySearchPath()
                    if (ffmpegFile.exists() && ffmpegFile.canExecute()) {
                        val pb = ProcessBuilder(ffmpegPath, "-version")
                            .redirectErrorStream(true)
                        pb.environment().putAll(ffmpegBinaryProvider.environment())
                        val process = pb.start()
                        val versionLine = process.inputStream.bufferedReader().readLine() ?: ""
                        val finished = waitForWithTimeout(process, 10)
                        if (!finished) {
                            Logger.w("SocialDL_Boot", "FFmpeg smoke test timed out after 10s; killing process")
                            destroyProcess(process)
                        } else {
                            val exitCode = process.exitValue()
                            Logger.i("SocialDL_Boot", "FFmpeg Smoke Test exit=$exitCode: $versionLine")
                        }
                    } else {
                        Logger.w("SocialDL_Boot", "FFmpeg binary not executable at: $ffmpegPath")
                    }

                    // 3. Node binary smoke test per Phase 0 exit criteria
                    val nodePath = nodeBinaryProvider.path()
                    val nodeFile = File(nodePath)
                    if (nodeFile.exists() && nodeFile.canExecute()) {
                        val pb = ProcessBuilder(nodePath, "-e", "console.log('NodeJS OK')")
                            .redirectErrorStream(true)
                        pb.environment().putAll(nodeBinaryProvider.environment())
                        val process = pb.start()
                        val nodeOutput = process.inputStream.bufferedReader().readLine() ?: ""
                        val finished = waitForWithTimeout(process, 10)
                        if (!finished) {
                            Logger.w("SocialDL_Boot", "Node smoke test timed out after 10s; killing process")
                            destroyProcess(process)
                        } else {
                            val exitCode = process.exitValue()
                            Logger.i("SocialDL_Boot", "Node Smoke Test exit=$exitCode: $nodeOutput")
                        }
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

