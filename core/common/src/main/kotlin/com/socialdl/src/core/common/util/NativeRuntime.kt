package com.socialdl.src.core.common.util

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Manages extraction of native support libraries from libffmpeg.zip.so and libnode.zip.so
 * to a shared app-private native runtime directory, resolves symlink stubs,
 * and exposes LD_LIBRARY_PATH and binary directories.
 */
object NativeRuntime {
    private const val TAG = "NativeRuntime"
    private val lock = Any()

    data class Paths(
        val runtimeDir: String,
        val usrLibDir: String,
        val binDir: String,
        val ldLibraryPath: String,
        val ffmpegPath: String,
        val nodePath: String,
        val environment: Map<String, String>
    )

    fun ensureExtracted(context: Context): Paths = synchronized(lock) {
        val runtimeDir = File(context.filesDir, "native_runtime")
        val usrLibDir = File(runtimeDir, "usr/lib")
        val binDir = File(runtimeDir, "bin")
        val readyMarker = File(runtimeDir, ".extracted_v4")

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val ldLibraryPath = listOf(
            usrLibDir.absolutePath,
            nativeDir,
            "/system/lib64"
        ).joinToString(":")
        val defaultEnv = mapOf(
            "LD_LIBRARY_PATH" to ldLibraryPath,
            "OPENSSL_CONF" to "/dev/null"
        )

        val ffmpegPath = File(nativeDir, "libffmpeg.so").absolutePath
        val nodePath = File(nativeDir, "libnode.so").absolutePath

        if (readyMarker.exists() && usrLibDir.exists() && (usrLibDir.list()?.isNotEmpty() == true)) {
            return@synchronized Paths(
                runtimeDir = runtimeDir.absolutePath,
                usrLibDir = usrLibDir.absolutePath,
                binDir = binDir.absolutePath,
                ldLibraryPath = ldLibraryPath,
                ffmpegPath = ffmpegPath,
                nodePath = nodePath,
                environment = defaultEnv
            )
        }

        try {
            Logger.d(TAG, "Extracting native runtime packages (ffmpeg & node)...")
            runtimeDir.deleteRecursively()
            usrLibDir.mkdirs()
            binDir.mkdirs()

            // 1. Extract libffmpeg.zip.so if present
            extractZipLib(File(nativeDir, "libffmpeg.zip.so"), runtimeDir)

            // 2. Extract libnode.zip.so if present
            extractZipLib(File(nativeDir, "libnode.zip.so"), runtimeDir)

            // 3. Resolve symlink stubs in usr/lib
            resolveSymlinkStubs(usrLibDir)

            // 4. Create convenience executable symlinks in bin/
            createSymlinkOrCopy(File(nativeDir, "libnode.so"), File(binDir, "node"))
            createSymlinkOrCopy(File(nativeDir, "libffmpeg.so"), File(binDir, "ffmpeg"))

            readyMarker.createNewFile()
            Logger.d(TAG, "Native runtime extracted successfully to ${runtimeDir.absolutePath}")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed during native runtime extraction", e)
        }

        Paths(
            runtimeDir = runtimeDir.absolutePath,
            usrLibDir = usrLibDir.absolutePath,
            binDir = binDir.absolutePath,
            ldLibraryPath = ldLibraryPath,
            ffmpegPath = ffmpegPath,
            nodePath = nodePath,
            environment = defaultEnv
        )
    }

    private fun extractZipLib(zipFile: File, destDir: File) {
        if (!zipFile.exists()) {
            Logger.w(TAG, "Zip lib not found: ${zipFile.absolutePath}")
            return
        }
        Logger.d(TAG, "Unzipping ${zipFile.name}...")
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val destFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    destFile.outputStream().use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun resolveSymlinkStubs(usrLibDir: File) {
        var resolvedAny = true
        var passes = 0
        while (resolvedAny && passes < 4) {
            resolvedAny = false
            passes++
            val files = usrLibDir.listFiles() ?: return
            for (file in files) {
                if (file.isFile && file.length() in 1..256) {
                    val targetName = try {
                        file.readText().trim()
                    } catch (e: Exception) {
                        ""
                    }
                    if (targetName.isNotEmpty() && !targetName.contains('/') && !targetName.contains('\n')) {
                        val targetFile = File(usrLibDir, targetName)
                        if (targetFile.exists() && targetFile.length() > file.length()) {
                            try {
                                targetFile.copyTo(file, overwrite = true)
                                resolvedAny = true
                            } catch (e: Exception) {
                                Logger.w(TAG, "Failed to copy target to symlink stub: ${file.name}", e)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun createSymlinkOrCopy(sourceFile: File, targetFile: File) {
        if (!sourceFile.exists()) return
        try {
            targetFile.delete()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Os.symlink(sourceFile.absolutePath, targetFile.absolutePath)
            } else {
                sourceFile.copyTo(targetFile, overwrite = true)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Could not symlink ${targetFile.name}, falling back to copy", e)
            try {
                sourceFile.copyTo(targetFile, overwrite = true)
            } catch (ignored: Exception) {}
        }
    }
}
