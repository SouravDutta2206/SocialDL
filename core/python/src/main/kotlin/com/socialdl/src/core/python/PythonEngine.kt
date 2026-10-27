package com.socialdl.src.core.python

import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.socialdl.src.core.common.di.PythonDispatcher
import com.socialdl.src.core.common.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coroutine-safe bridge to the Chaquopy Python runtime.
 *
 * Guiding principles (§2.1 / §2.2 / §Phase 2):
 * 1. Lazily starts Chaquopy on a dedicated single-thread [PythonDispatcher] (Python's GIL).
 * 2. Enforces a JSON-only boundary: scripts always return JSON strings; Kotlin parses them.
 * 3. Wraps calls in [runInterruptible] + timeout, converting tracebacks to [PythonExecutionException].
 */
@Singleton
open class PythonEngine @Inject constructor(
    @PythonDispatcher private val pythonDispatcher: CoroutineDispatcher
) {
    private val tag = "PythonEngine"

    /**
     * Executes a Python function and parses its JSON string return value.
     *
     * @param module The dot-separated Python module path (e.g. "common.ping", "yt_dlp_bridge.extract").
     * @param function The function name to call.
     * @param args Positional arguments to pass to the function.
     * @param timeoutMs Timeout in milliseconds (default 60 seconds).
     * @param redactArgs When true, arguments are omitted from the log line — use for
     *   credential-bearing calls (Instagram login). Never log secrets.
     * @param parse Function to parse the returned JSON string into type [T].
     */
    open suspend fun <T> run(
        module: String,
        function: String,
        vararg args: Any?,
        timeoutMs: Long = 60_000L,
        redactArgs: Boolean = false,
        parse: (String) -> T
    ): T = withContext(pythonDispatcher) {
        // Chaquopy starts on a background thread during app boot; every call
        // waits for it here instead of blocking Application.onCreate.
        PythonBootstrap.awaitStarted()
        withTimeout(timeoutMs) {
            runInterruptible {
                try {
                    if (redactArgs) {
                        Logger.d(tag, "Invoking $module.$function(<redacted>)")
                    } else {
                        Logger.d(tag, "Invoking $module.$function(${args.joinToString()})")
                    }
                    val py = Python.getInstance()
                    val pyModule = py.getModule(module)
                    val pyFunction = pyModule[function]
                        ?: throw PythonExecutionException(
                            traceback = "Function '$function' not found in module '$module'",
                            message = "Function not found"
                        )

                    val result = if (args.isEmpty()) {
                        pyFunction.call()
                    } else {
                        pyFunction.call(*args)
                    }

                    val jsonString = result?.toString() ?: ""
                    Logger.d(tag, "$module.$function returned ${jsonString.length} chars")
                    parse(jsonString)
                } catch (e: CancellationException) {
                    // Timeout (withTimeout) and cancellation (runInterruptible) must
                    // propagate structurally — converting them to a parse error would
                    // mask "user cancelled"/"timed out" and break the caller's
                    // CancellationException contract. TimeoutCancellationException
                    // escaping withTimeout is the normal timeout signal.
                    throw e
                } catch (e: PyException) {
                    val traceback = e.message ?: "Unknown Python error"
                    Logger.e(tag, "Python error in $module.$function: $traceback", e)
                    throw PythonExecutionException(
                        traceback = traceback,
                        // Keep the traceback out of the message: this string flows
                        // into user-facing extraction error cards.
                        message = "Python execution error in $module.$function",
                        cause = e
                    )
                } catch (e: Exception) {
                    if (e is PythonExecutionException) throw e
                    Logger.e(tag, "Unexpected error in $module.$function", e)
                    throw PythonExecutionException(
                        traceback = e.stackTraceToString(),
                        message = e.message ?: "Unexpected error",
                        cause = e
                    )
                }
            }
        }
    }

    /**
     * Helper to verify Chaquopy runtime is initialized and accessible.
     */
    fun isAvailable(): Boolean {
        return try {
            Python.isStarted()
        } catch (e: Exception) {
            false
        }
    }
}
