package com.socialdl.src.core.runtime

import android.os.Build
import android.os.Process
import android.system.Os
import com.socialdl.src.core.common.util.Logger
import java.io.File
import java.util.zip.ZipFile

/**
 * Builds the support-library tree for one component source:
 *
 *     <runtimeRoot>/<component>/<source key>/
 *         usr/lib/   libraries unpacked from the component's payload zip
 *         bin/<id>   symlink to the executable in its nativeLibraryDir
 *
 * The executable always runs from its nativeLibraryDir; only the libraries are
 * unpacked. The tree is a disposable cache. It is built in a staging directory and
 * renamed into place, so an interrupted run never leaves a tree that looks ready.
 */
internal object NativeRuntime {
    private const val TAG = "NativeRuntime"
    private const val LAYOUT_VERSION = 9
    private const val MARKER = ".ready"
    private const val STAGING = ".staging"
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private val STUB_SIZE_BYTES = 1L..256L
    private val ELF_MAGIC = "\u007fELF".toByteArray(Charsets.US_ASCII)

    /**
     * Returns the ready runtime for [source], unpacking it first if needed, or null if
     * it cannot be used.
     *
     * Ordering is load-bearing: the source is verified ([readyCheck]) BEFORE
     * [prune] deletes the component's other source trees. Pruning first let an
     * installed-but-unusable helper APK wipe the working bundled runtime and leave
     * the component unavailable.
     *
     * [is64Bit] / [readyCheck] are injectable seams for JVM tests; production
     * callers use the defaults.
     */
    fun prepare(
        runtimeRoot: File,
        spec: NativeComponentSpec,
        source: NativeComponentSource,
        is64Bit: Boolean = Process.is64Bit(),
        readyCheck: (ResolvedNativeComponent) -> Unit = { ensureReady(it) },
    ): ResolvedNativeComponent? {
        val componentDir = File(runtimeRoot, spec.id)
        val runtimeDir = File(componentDir, source.key)
        val runtime = ResolvedNativeComponent(spec, source, runtimeDir, ldLibraryPath(runtimeDir, is64Bit))
        return try {
            readyCheck(runtime)
            prune(runtimeRoot, componentDir, keep = source.key)
            runtime
        } catch (e: Exception) {
            Logger.e(TAG, "${spec.displayName}: ${source.kind} runtime unusable", e)
            null
        }
    }

    private fun ensureReady(runtime: ResolvedNativeComponent) {
        val payload = File(runtime.source.nativeLibraryDir, runtime.spec.payloadName)
        check(isElf(runtime.executable)) { "${runtime.spec.executableName} is missing or not an ELF file" }
        check(payload.isFile) { "${runtime.spec.payloadName} is missing" }

        val expected = markerText(runtime, payload)
        val marker = File(runtime.runtimeDir, MARKER)
        val upToDate = runCatching { marker.readText() }.getOrNull() == expected &&
            File(runtime.runtimeDir, "usr/lib").list()?.isNotEmpty() == true
        if (!upToDate) unpack(runtime, payload, expected)
        linkExecutable(runtime)
    }

    private fun unpack(runtime: ResolvedNativeComponent, payload: File, marker: String) {
        val startedAt = System.currentTimeMillis()
        val staging = File(runtime.runtimeDir.parentFile, STAGING)
        staging.deleteRecursively()
        try {
            unzip(payload, staging)
            val usrLib = File(staging, "usr/lib")
            linkStubs(usrLib)
            check(usrLib.list()?.isNotEmpty() == true) { "${payload.name} contains no libraries" }
            File(staging, MARKER).writeText(marker)
            runtime.runtimeDir.deleteRecursively()
            check(staging.renameTo(runtime.runtimeDir)) { "could not move runtime into place" }
        } finally {
            staging.deleteRecursively()
        }
        Logger.i(
            TAG,
            "${runtime.spec.displayName}: unpacked ${payload.name} in " +
                "${System.currentTimeMillis() - startedAt} ms",
        )
    }

    /** Payloads are self-contained: only their unpacked libraries and the system libraries. */
    private fun ldLibraryPath(runtimeDir: File, is64Bit: Boolean): String = listOf(
        File(runtimeDir, "usr/lib").absolutePath,
        if (is64Bit) "/system/lib64" else "/system/lib",
    ).joinToString(":")

    /** The nativeLibraryDir changes across installs, so the link is refreshed on every prepare. */
    private fun linkExecutable(runtime: ResolvedNativeComponent) {
        runtime.binDir.mkdirs()
        val link = File(runtime.binDir, runtime.spec.id)
        link.delete()
        Os.symlink(runtime.executable.absolutePath, link.absolutePath)
    }

    /**
     * Payload symlinks are stored as tiny text files that name their target. Replace them
     * with real symlinks so the linker finds SONAME names such as libz.so.1. The kernel
     * follows chains of links, so a stub may point at another stub.
     */
    private fun linkStubs(usrLib: File) {
        for (stub in usrLib.listFiles().orEmpty()) {
            if (!stub.isFile || stub.length() !in STUB_SIZE_BYTES) continue
            val target = stub.readText().trim()
            val isFileName = target.isNotEmpty() && target != stub.name &&
                '/' !in target && target.none { it.isWhitespace() }
            if (!isFileName || !File(usrLib, target).exists()) continue
            stub.delete()
            Os.symlink(target, stub.absolutePath)
        }
    }

    /** Extracts [zip] into [destination], refusing any entry that would resolve outside it. */
    internal fun unzip(zip: File, destination: File) {
        destination.mkdirs()
        val root = destination.canonicalPath + File.separator
        ZipFile(zip).use { archive ->
            for (entry in archive.entries()) {
                val target = File(destination, entry.name)
                require(target.canonicalPath.startsWith(root)) { "zip entry escapes runtime: ${entry.name}" }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    archive.getInputStream(entry).use { input ->
                        target.outputStream().use { input.copyTo(it, COPY_BUFFER_BYTES) }
                    }
                }
            }
        }
    }

    /** Removes entries that are not current component directories, and this component's other source trees. */
    private fun prune(runtimeRoot: File, componentDir: File, keep: String) {
        runtimeRoot.listFiles().orEmpty()
            .filter { entry -> NativeComponents.all.none { it.id == entry.name } }
            .forEach { it.deleteRecursively() }
        componentDir.listFiles().orEmpty()
            .filter { it.name != keep }
            .forEach { it.deleteRecursively() }
    }

    private fun markerText(runtime: ResolvedNativeComponent, payload: File): String = listOf(
        "layout=$LAYOUT_VERSION",
        "component=${runtime.spec.id}",
        "source=${runtime.source.key}",
        "abi=${Build.SUPPORTED_ABIS.firstOrNull()}",
        "payload=${payload.length()}:${payload.lastModified()}",
    ).joinToString("\n")

    private fun isElf(file: File): Boolean = file.isFile && runCatching {
        file.inputStream().use { input ->
            val magic = ByteArray(ELF_MAGIC.size)
            input.read(magic) == magic.size && magic.contentEquals(ELF_MAGIC)
        }
    }.getOrDefault(false)
}
