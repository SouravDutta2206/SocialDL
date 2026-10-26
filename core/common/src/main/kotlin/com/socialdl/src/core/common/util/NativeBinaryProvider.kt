package com.socialdl.src.core.common.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File

/**
 * Shared implementation for the per-binary native providers (ffmpeg, node):
 * wraps [NativeRuntime.ensureExtracted], resolves the binary from the extracted
 * runtime paths, and owns the nullable-context JVM-test seam (fakes pass null
 * and override every context-touching member; Dagger always supplies the real
 * application context).
 *
 * Subclasses name the binary and select its path from [NativeRuntime.Paths] —
 * every behavioral detail (path resolution, availability, env) lives HERE so
 * the two providers cannot drift.
 */
open class NativeBinaryProvider(
    private val binaryName: String,
    private val binaryPathSelector: (NativeRuntime.Paths) -> String,
    @ApplicationContext protected val context: Context?,
) {
    private val tag = binaryName

    /**
     * Wraps [NativeRuntime.ensureExtracted] and surfaces extraction failure loudly
     * instead of silently returning a broken runtime.
     */
    protected fun resolveRuntime(): NativeRuntime.Paths? {
        if (context == null) return null
        val paths = NativeRuntime.ensureExtracted(context)
        if (paths.extractionFailed) {
            Logger.e(tag, "native runtime extraction failed; $binaryName binary may not work")
        }
        return paths
    }

    open fun path(): String {
        if (context == null) return binaryName
        val paths = resolveRuntime() ?: return binaryName
        val binaryFile = File(binaryPathSelector(paths))

        if (binaryFile.exists() && binaryFile.canExecute()) {
            Logger.d(tag, "Resolved $binaryName binary at: ${binaryFile.absolutePath}")
            return binaryFile.absolutePath
        }

        Logger.w(tag, "$binaryName binary at ${binaryFile.absolutePath} exists=${binaryFile.exists()}, canExecute=${binaryFile.canExecute()}")
        return binaryFile.absolutePath
    }

    open fun isAvailable(): Boolean {
        if (context == null) return false
        val paths = resolveRuntime() ?: return false
        val binaryFile = File(binaryPathSelector(paths))
        return binaryFile.exists() && binaryFile.canExecute()
    }

    open fun environment(): Map<String, String> {
        if (context == null) return emptyMap()
        return resolveRuntime()?.environment ?: emptyMap()
    }

    open fun librarySearchPath(): String {
        if (context == null) return ""
        return resolveRuntime()?.ldLibraryPath ?: ""
    }
}
