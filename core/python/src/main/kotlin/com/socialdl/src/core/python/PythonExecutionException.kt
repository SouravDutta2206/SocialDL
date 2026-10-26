package com.socialdl.src.core.python

/**
 * Typed exception carrying the Python traceback string for error diagnostics and the Logs screen.
 *
 * [traceback] stays OUT of [message]: the message surfaces in user-facing UI
 * (toast/error cards via extraction error mapping), while the traceback is for
 * logs only.
 */
class PythonExecutionException(
    val traceback: String,
    message: String = "Python execution failed",
    cause: Throwable? = null,
) : Exception(message, cause)
