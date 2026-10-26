package com.socialdl.src.core.node

import android.content.Context
import com.socialdl.src.core.common.util.NativeBinaryProvider
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
    @ApplicationContext context: Context?
) : NativeBinaryProvider(
    binaryName = "node",
    binaryPathSelector = { paths: NativeRuntime.Paths -> paths.nodePath },
    context = context,
) {
    open fun binaryDir(): String {
        return File(path()).parent ?: ""
    }
}
