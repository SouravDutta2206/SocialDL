package com.socialdl.src.core.python

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.socialdl.src.core.common.util.Logger
import kotlinx.coroutines.CancellationException
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
 * A failed startup is not permanent: [begin]'s catch resets [readiness] so the
 * next [awaitStarted] retries `Python.start` instead of rethrowing the same
 * stale failure for the life of the process.
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

    /** Application context captured by [begin], used to retry a failed start. */
    @Volatile
    private var appContext: Context? = null

    /** Kicks off Chaquopy initialization once; safe to call repeatedly. */
    fun begin(context: Context) {
        val application = context.applicationContext
        appContext = application
        if (Python.isStarted()) return
        val deferred = synchronized(lock) {
            if (readiness != null) return
            CompletableDeferred<Unit>().also { readiness = it }
        }
        scope.launch {
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(application))
                    Logger.i(tag, "Chaquopy Python runtime started")
                }
                deferred.complete(Unit)
            } catch (e: CancellationException) {
                synchronized(lock) {
                    if (readiness === deferred) readiness = null
                }
                deferred.cancel(e)
                throw e
            } catch (e: Throwable) {
                Logger.e(tag, "Chaquopy Python runtime failed to start", e)
                // Transient startup failures (native load races, low memory, …)
                // must not brick Python extraction for the rest of the process:
                // drop the failed attempt so the next awaitStarted retries it.
                synchronized(lock) {
                    if (readiness === deferred) readiness = null
                }
                deferred.completeExceptionally(e)
                if (e is Error) throw e
            }
        }
    }

    /** Suspends until startup completes (or the timeout elapses). */
    suspend fun awaitStarted(timeoutMs: Long = 60_000L) {
        if (Python.isStarted()) return

        val deferred = readiness ?: run {
            // Desktop unit tests never call begin(): with no stored context there
            // is nothing to retry, so preserve the immediate-return behavior.
            val context = appContext ?: return
            begin(context)
            readiness ?: return
        }
        withTimeout(timeoutMs) { deferred.await() }
    }
}
