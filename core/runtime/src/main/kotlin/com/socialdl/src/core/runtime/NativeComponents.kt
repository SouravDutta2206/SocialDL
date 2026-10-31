package com.socialdl.src.core.runtime

import java.io.File

/** A native tool SocialDL can run, from its bundled jniLibs or a helper APK. */
data class NativeComponentSpec(
    val id: String,
    val displayName: String,
    /** Package name of the optional helper APK for this component. */
    val helperPackage: String,
    /** Executable inside the nativeLibraryDir, e.g. libffmpeg.so. */
    val executableName: String,
    /** Support-library zip next to the executable, e.g. libffmpeg.zip.so. */
    val payloadName: String,
    val baselineVersion: String,
)

/**
 * The only components SocialDL looks for. Helper packages are queried by these exact
 * names; arbitrary installed packages are never scanned.
 */
object NativeComponents {
    val FFMPEG = NativeComponentSpec(
        id = "ffmpeg",
        displayName = "FFmpeg",
        helperPackage = "com.socialdl.src.helper.ffmpeg",
        executableName = "libffmpeg.so",
        payloadName = "libffmpeg.zip.so",
        baselineVersion = "7.1.1",
    )

    val NODE = NativeComponentSpec(
        id = "node",
        displayName = "Node",
        helperPackage = "com.socialdl.src.helper.node",
        executableName = "libnode.so",
        payloadName = "libnode.zip.so",
        baselineVersion = "25.3.0",
    )

    val all: List<NativeComponentSpec> = listOf(FFMPEG, NODE)
}

enum class NativeSourceKind { BUNDLED, HELPER }

/**
 * Where a component's binaries come from. [key] names the runtime directory, so a
 * different helper build gets its own tree.
 */
data class NativeComponentSource(
    val kind: NativeSourceKind,
    val key: String,
    val nativeLibraryDir: File,
    val version: String,
    val packageName: String? = null,
)

/** A component whose support libraries are unpacked and ready to run. */
data class ResolvedNativeComponent(
    val spec: NativeComponentSpec,
    val source: NativeComponentSource,
    val runtimeDir: File,
    /**
     * Payloads are self-contained, so only the unpacked libraries and the system
     * libraries are searched. The source's nativeLibraryDir is deliberately absent.
     */
    val ldLibraryPath: String,
) {
    val executable: File
        get() = File(source.nativeLibraryDir, spec.executableName)

    /** Holds `bin/<id>`, a symlink to [executable]. */
    val binDir: File
        get() = File(runtimeDir, "bin")

    val environment: Map<String, String>
        get() = mapOf(
            "LD_LIBRARY_PATH" to ldLibraryPath,
            "OPENSSL_CONF" to "/dev/null",
        )
}
