package com.socialdl.src.core.python

/**
 * Typed exception carrying the Python traceback string for error diagnostics and the Logs screen.
 */
class PythonExecutionException(
    val traceback: String,
    message: String = "Python execution failed: $traceback",
    cause: Throwable? = null,
) : Exception(message, cause)
