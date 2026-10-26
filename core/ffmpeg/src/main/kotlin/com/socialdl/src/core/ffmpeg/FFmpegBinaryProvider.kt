package com.socialdl.src.core.ffmpeg

import android.content.Context
import com.socialdl.src.core.common.util.NativeBinaryProvider
import com.socialdl.src.core.common.util.NativeRuntime
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext context: Context?
) : NativeBinaryProvider(
    binaryName = "ffmpeg",
    binaryPathSelector = { paths: NativeRuntime.Paths -> paths.ffmpegPath },
    context = context,
) {
    open fun ensureExtracted(): String? {
        if (context == null) return null
        return resolveRuntime()?.usrLibDir
    }
}
