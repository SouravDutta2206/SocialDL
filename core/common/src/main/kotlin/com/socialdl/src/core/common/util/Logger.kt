package com.socialdl.src.core.common.util

import android.util.Log

/**
 * Simple logging facade wrapping android.util.Log.
 * All log calls go through here for consistent tagging and future Logs screen integration.
 */
object Logger {
    private const val TAG_PREFIX = "SocialDL"

    fun d(tag: String, message: String) {
        try {
            Log.d("$TAG_PREFIX:$tag", message)
        } catch (_: Throwable) {
            println("D/$TAG_PREFIX:$tag: $message")
        }
    }

    fun i(tag: String, message: String) {
        try {
            Log.i("$TAG_PREFIX:$tag", message)
        } catch (_: Throwable) {
            println("I/$TAG_PREFIX:$tag: $message")
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) {
                Log.w("$TAG_PREFIX:$tag", message, throwable)
            } else {
                Log.w("$TAG_PREFIX:$tag", message)
            }
        } catch (_: Throwable) {
            println("W/$TAG_PREFIX:$tag: $message")
            throwable?.printStackTrace()
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) {
                Log.e("$TAG_PREFIX:$tag", message, throwable)
            } else {
                Log.e("$TAG_PREFIX:$tag", message)
            }
        } catch (_: Throwable) {
            System.err.println("E/$TAG_PREFIX:$tag: $message")
            throwable?.printStackTrace()
        }
    }
}
