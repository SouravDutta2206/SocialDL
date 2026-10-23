package com.socialdl.src

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.ffmpeg.FFmpegBinaryProvider
import com.socialdl.src.core.node.NodeBinaryProvider
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()


    override fun onCreate() {
        super.onCreate()
        // Initialize Chaquopy Python runtime if not already started
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        runDiagnosticsSmokeTest()
    }

    private fun runDiagnosticsSmokeTest() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Python smoke test: call common.ping per Phase 0 & Phase 2 exit criteria
                val py = Python.getInstance()
                val pingModule = py.getModule("common.ping")
                val pingResult = pingModule.callAttr("ping", "SocialDL Phase 0/2 Boot Check")
                Logger.i("SocialDL_Boot", "Chaquopy Smoke Test OK: $pingResult")

                // Pre-warm yt_dlp module so the first user search avoids Python import lag
                try {
                    val extractModule = py.getModule("yt_dlp_bridge.extract")
                    extractModule.callAttr("warmup")
                    Logger.i("SocialDL_Boot", "Chaquopy yt_dlp bridge pre-warmed")
                } catch (e: Exception) {
                    Logger.w("SocialDL_Boot", "yt_dlp bridge pre-warm non-critical error: ${e.message}")
                }

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
                    val exitCode = process.waitFor()
                    Logger.i("SocialDL_Boot", "FFmpeg Smoke Test exit=$exitCode: $versionLine")
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
                    val exitCode = process.waitFor()
                    Logger.i("SocialDL_Boot", "Node Smoke Test exit=$exitCode: $nodeOutput")
                } else {
                    Logger.w("SocialDL_Boot", "Node binary not executable at: $nodePath")
                }

            } catch (e: Exception) {
                Logger.e("SocialDL_Boot", "Diagnostics smoke test encountered an error", e)
            }
        }
    }
}

