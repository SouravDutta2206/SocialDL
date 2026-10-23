package com.socialdl.src.core.common.result

/**
 * Typed error hierarchy for cross-module error communication.
 */
sealed class AppError(
    open val message: String,
    open val cause: Throwable? = null,
) {
    /** Network-related failure (timeout, no connectivity, HTTP error). */
    data class NetworkError(
        override val message: String = "Network error",
        override val cause: Throwable? = null,
    ) : AppError(message, cause)

    /** Storage/file-system failure (no space, permission denied). */
    data class StorageError(
        override val message: String = "Storage error",
        override val cause: Throwable? = null,
    ) : AppError(message, cause)

    /** Python/Chaquopy execution failure — carries the full traceback for Logs screen. */
    data class PythonError(
        val traceback: String,
        override val message: String = "Python execution error",
        override val cause: Throwable? = null,
    ) : AppError(message, cause)

    /** Catch-all for unexpected errors. */
    data class Unknown(
        override val message: String = "Unknown error",
        override val cause: Throwable? = null,
    ) : AppError(message, cause)

    fun toException(): Exception = when (this) {
        is NetworkError -> RuntimeException(message, cause)
        is StorageError -> RuntimeException(message, cause)
        is PythonError -> RuntimeException("$message\n$traceback", cause)
        is Unknown -> RuntimeException(message, cause)
    }
}
