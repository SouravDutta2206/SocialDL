package com.socialdl.src.core.node

import android.content.Context
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.common.util.NativeRuntime
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the path to the statically-linked Node binary (§2.5.3).
 *
 * Sourced binary location:
 * Extracted to context.applicationInfo.nativeLibraryDir/libnode.so by Android's package manager
 * from src/main/jniLibs/<abi>/libnode.so at install time.
 * Support libraries are extracted from libnode.zip.so via NativeRuntime.
 */
@Singleton
open class NodeBinaryProvider @Inject constructor(
    // Nullable only as a JVM-test seam (fakes pass null and override the
    // context-touching members); Dagger always supplies the real application context.
    @ApplicationContext private val context: Context?
) {
    private val tag = "NodeBinaryProvider"

    /**
     * Wraps [NativeRuntime.ensureExtracted] and surfaces extraction failure loudly
     * instead of silently returning a broken runtime.
     */
    internal fun resolveRuntime(): NativeRuntime.Paths? {
        if (context == null) return null
        val paths = NativeRuntime.ensureExtracted(context)
        if (paths.extractionFailed) {
            Logger.e(tag, "native runtime extraction failed; node binary may not work")
        }
        return paths
    }

    open fun path(): String {
        if (context == null) return "node"
        val paths = resolveRuntime() ?: return "node"
        val binaryFile = File(paths.nodePath)

        if (binaryFile.exists() && binaryFile.canExecute()) {
            Logger.d(tag, "Resolved node binary at: ${binaryFile.absolutePath}")
            return binaryFile.absolutePath
        }

        Logger.w(tag, "node binary at ${binaryFile.absolutePath} exists=${binaryFile.exists()}, canExecute=${binaryFile.canExecute()}")
        return binaryFile.absolutePath
    }

    open fun isAvailable(): Boolean {
        if (context == null) return false
        val paths = resolveRuntime() ?: return false
        val binaryFile = File(paths.nodePath)
        return binaryFile.exists() && binaryFile.canExecute()
    }

    open fun binaryDir(): String {
        return File(path()).parent ?: ""
    }

    open fun environment(): Map<String, String> {
        if (context == null) return emptyMap()
        return resolveRuntime()?.environment ?: emptyMap()
    }
}
