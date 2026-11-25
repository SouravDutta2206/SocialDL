package com.socialdl.src

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.python.PythonBootstrap
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class SocialDLApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

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

    // Guarded scope for background startup work: an uncaught error must never
    // take the Application down.
    private val startupScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Logger.e("SocialDL_Boot", "Startup background task crashed", e)
        }
    )

    override fun onCreate() {
        super.onCreate()
        // Start Chaquopy on a background thread. PythonEngine awaits readiness
        // before its first call, so onCreate no longer blocks the main thread.
        PythonBootstrap.begin(this)

        // Foreground download notification channel (P1-1) — created once at app start.
        notificationManager.ensureChannel()

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
        startupScope.launch {
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
}
