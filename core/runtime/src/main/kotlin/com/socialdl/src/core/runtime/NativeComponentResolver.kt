package com.socialdl.src.core.runtime

import android.content.Context
import com.socialdl.src.core.common.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks each component's runtime once per process. An installed helper wins when its
 * signer is trusted and its runtime can be prepared; otherwise the bundled jniLibs are
 * used. Versions are not compared.
 */
@Singleton
class NativeComponentResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Only successfully resolved components are cached. A transient failure
    // (helper APK mid-install, storage momentarily unavailable) must be retried
    // by the next resolve() instead of being remembered as "unavailable" for the
    // rest of the process.
    private val resolved = HashMap<NativeComponentSpec, ResolvedNativeComponent>()

    /** The runtime to use for [spec], or null if the component is unavailable. */
    @Synchronized
    fun resolve(spec: NativeComponentSpec): ResolvedNativeComponent? {
        val cached = resolved[spec]
        if (cached != null) {
            if (cached.executable.canExecute()) return cached
            // The helper APK can be uninstalled (or the app updated) while this
            // process runs, which leaves the cached path dangling. Drop it and
            // pick a source again instead of failing every later call.
            Logger.w(TAG, "${spec.displayName}: cached ${cached.source.kind} runtime is gone; re-resolving")
            resolved.remove(spec)
        }
        return select(spec)?.also { resolved[spec] = it }
    }

    private fun select(spec: NativeComponentSpec): ResolvedNativeComponent? {
        var fallbackReason: String? = null
        findHelperPackage(context, spec)?.let { helper ->
            val rejection = HelperPackageVerifier.rejectionReason(
                helper = helper,
                pinnedSigners = BuildConfig.HELPER_CERT_SHA256,
                debugBuild = BuildConfig.DEBUG,
            )
            val runtime = if (rejection == null) {
                NativeRuntime.prepare(runtimeRoot(), spec, helperSource(helper))
            } else {
                null
            }
            if (runtime != null) {
                report(runtime, fallbackReason = null)
                return runtime
            }
            fallbackReason = rejection ?: "helper runtime could not be prepared"
        }
        return NativeRuntime.prepare(runtimeRoot(), spec, bundledSource(spec))
            ?.also { report(it, fallbackReason) }
    }

    private fun report(runtime: ResolvedNativeComponent, fallbackReason: String?) {
        val source = runtime.source
        val line = "${runtime.spec.displayName}: source=${source.kind} " +
            "package=${source.packageName ?: "-"} version=${source.version} " +
            "path=${runtime.executable.absolutePath}"
        if (fallbackReason == null) {
            Logger.i(TAG, line)
        } else {
            Logger.w(TAG, "$line fallbackReason=$fallbackReason")
        }
    }

    private fun helperSource(helper: HelperPackage) = NativeComponentSource(
        kind = NativeSourceKind.HELPER,
        key = "helper-${helper.versionCode}",
        nativeLibraryDir = helper.nativeLibraryDir,
        version = helper.versionName ?: "unknown",
        packageName = helper.packageName,
    )

    private fun bundledSource(spec: NativeComponentSpec) = NativeComponentSource(
        kind = NativeSourceKind.BUNDLED,
        key = "bundled",
        nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
        version = spec.baselineVersion,
    )

    /** Outside filesDir so Auto Backup and device transfer skip these large, rebuildable trees. */
    private fun runtimeRoot(): File = File(context.noBackupFilesDir, "native_runtime")

    private companion object {
        const val TAG = "NativeResolver"
    }
}

/**
 * Shared implementation of the FFmpeg and Node providers. [resolver] is null only in
 * JVM tests, where subclasses override the members they use.
 */
open class NativeBinaryProvider(
    private val spec: NativeComponentSpec,
    private val resolver: NativeComponentResolver?,
) {
    /** The usable runtime, or null when the component is unavailable. */
    open fun resolveRuntime(): ResolvedNativeComponent? = resolver?.resolve(spec)

    open fun path(): String = resolveRuntime()?.executable?.absolutePath ?: spec.executableName

    open fun isAvailable(): Boolean = resolveRuntime() != null

    open fun environment(): Map<String, String> = resolveRuntime()?.environment ?: emptyMap()

    open fun librarySearchPath(): String = resolveRuntime()?.ldLibraryPath.orEmpty()
}
