package com.socialdl.src.core.common.util

import android.util.Log

/**
 * Simple logging facade wrapping android.util.Log.
 * All log calls go through here for consistent tagging and future Logs screen integration.
 */
object Logger {
    private const val TAG_PREFIX = "SocialDL"

    fun d(tag: String, message: String) = logInternal('D', tag, message)

    fun i(tag: String, message: String) = logInternal('I', tag, message)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        logInternal('W', tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        logInternal('E', tag, message, throwable)

    /**
     * Single implementation for all levels: try Logcat first, fall back to stdout
     * (unit-test JVMs have no android.util.Log) and re-throw nothing.
     */
    private fun logInternal(level: Char, tag: String, message: String, throwable: Throwable? = null) {
        val prefixedTag = "$TAG_PREFIX:$tag"
        try {
            when (level) {
                'D' -> Log.d(prefixedTag, message)
                'I' -> Log.i(prefixedTag, message)
                'W' ->
                    if (throwable != null) {
                        Log.w(prefixedTag, message, throwable)
                    } else {
                        Log.w(prefixedTag, message)
                    }
                else ->
                    if (throwable != null) {
                        Log.e(prefixedTag, message, throwable)
                    } else {
                        Log.e(prefixedTag, message)
                    }
            }
        } catch (_: Throwable) {
            when (level) {
                'E' -> {
                    System.err.println("$level/$prefixedTag: $message")
                    throwable?.printStackTrace()
                }
                'D', 'I' -> println("$level/$prefixedTag: $message")
                else -> {
                    println("$level/$prefixedTag: $message")
                    throwable?.printStackTrace()
                }
            }
        }
    }
}
