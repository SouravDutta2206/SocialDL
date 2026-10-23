package com.socialdl.src.core.python

import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.socialdl.src.core.common.di.PythonDispatcher
import com.socialdl.src.core.common.util.Logger
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
     * @param parse Function to parse the returned JSON string into type [T].
     */
    open suspend fun <T> run(
        module: String,
        function: String,
        vararg args: Any?,
        timeoutMs: Long = 60_000L,
        parse: (String) -> T
    ): T = withContext(pythonDispatcher) {
        withTimeout(timeoutMs) {
            runInterruptible {
                try {
                    Logger.d(tag, "Invoking $module.$function(${args.joinToString()})")
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
                } catch (e: PyException) {
                    val traceback = e.message ?: "Unknown Python error"
                    Logger.e(tag, "Python error in $module.$function: $traceback", e)
                    throw PythonExecutionException(
                        traceback = traceback,
                        message = "Python execution error: $traceback",
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
