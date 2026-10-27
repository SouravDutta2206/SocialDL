package com.socialdl.src.core.python

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.socialdl.src.core.common.util.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Owns Chaquopy startup off the main thread.
 *
 * `Python.start(AndroidPlatform(context))` used to run directly in
 * `Application.onCreate`, which can block for seconds and produce startup jank.
 * Instead the application calls [begin] (returns immediately) and every bridge
 * call awaits [awaitStarted] before touching `Python.getInstance()`.
 *
 * Desktop unit tests never call [begin], so [awaitStarted] returns immediately
 * and the pre-existing "Python not started" behavior is preserved.
 */
object PythonBootstrap {

    private val tag = "PythonBootstrap"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var readiness: CompletableDeferred<Unit>? = null

    /** Kicks off Chaquopy initialization once; safe to call repeatedly. */
    fun begin(context: Context) {
        if (Python.isStarted()) return
        val deferred = synchronized(lock) {
            if (readiness != null) return
            CompletableDeferred<Unit>().also { readiness = it }
        }
        scope.launch {
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context.applicationContext))
                    Logger.i(tag, "Chaquopy Python runtime started")
                }
            } catch (e: Exception) {
                Logger.e(tag, "Chaquopy Python runtime failed to start", e)
            } finally {
                deferred.complete(Unit)
            }
        }
    }

    /** Suspends until startup completes (or the timeout elapses). */
    suspend fun awaitStarted(timeoutMs: Long = 60_000L) {
        if (Python.isStarted()) return
        val deferred = readiness ?: return
        withTimeout(timeoutMs) { deferred.await() }
    }
}
