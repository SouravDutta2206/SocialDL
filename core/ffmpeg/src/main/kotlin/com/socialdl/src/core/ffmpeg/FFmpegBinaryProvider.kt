package com.socialdl.src.core.ffmpeg

import android.content.Context
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.common.util.NativeRuntime
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the path to the statically-linked ffmpeg binary (§2.4 & §2.5.3).
 *
 * Sourced binary location:
 * Extracted to context.applicationInfo.nativeLibraryDir/libffmpeg.so by Android's package manager
 * from src/main/jniLibs/<abi>/libffmpeg.so at install time.
 * Support libraries are extracted from libffmpeg.zip.so / libnode.zip.so via NativeRuntime.
 */
@Singleton
open class FFmpegBinaryProvider @Inject constructor(
    // Nullable only as a JVM-test seam (fakes pass null and override every
    // context-touching member); Dagger always supplies the real application context.
    @ApplicationContext private val context: Context?
) {
    private val tag = "FFmpegBinaryProvider"

    /**
     * Wraps [NativeRuntime.ensureExtracted] and surfaces extraction failure loudly
     * instead of silently returning a broken runtime.
     */
    internal fun resolveRuntime(): NativeRuntime.Paths? {
        if (context == null) return null
        val paths = NativeRuntime.ensureExtracted(context)
        if (paths.extractionFailed) {
            Logger.e(tag, "native runtime extraction failed; ffmpeg binary may not work")
        }
        return paths
    }

    open fun path(): String {
        if (context == null) return "ffmpeg"
        val paths = resolveRuntime() ?: return "ffmpeg"
        val binaryFile = File(paths.ffmpegPath)

        if (binaryFile.exists() && binaryFile.canExecute()) {
            Logger.d(tag, "Resolved ffmpeg binary at: ${binaryFile.absolutePath}")
            return binaryFile.absolutePath
        }

        Logger.w(tag, "ffmpeg binary at ${binaryFile.absolutePath} exists=${binaryFile.exists()}, canExecute=${binaryFile.canExecute()}")
        return binaryFile.absolutePath
    }

    open fun isAvailable(): Boolean {
        if (context == null) return false
        val file = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        return file.exists() && file.canExecute()
    }

    open fun ensureExtracted(): String? {
        if (context == null) return null
        return resolveRuntime()?.usrLibDir
    }

    open fun librarySearchPath(): String {
        if (context == null) return ""
        return resolveRuntime()?.ldLibraryPath ?: ""
    }

    open fun environment(): Map<String, String> {
        if (context == null) return emptyMap()
        return resolveRuntime()?.environment ?: emptyMap()
    }
}
