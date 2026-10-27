package com.socialdl.src.core.common.util

import android.content.Context
import android.os.Build
import android.os.Process
import android.system.Os
import java.io.File
import java.io.IOException
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
        val environment: Map<String, String>,
        /** True when extraction threw and the runtime contents may be incomplete. */
        val extractionFailed: Boolean = false
    )

    /** Bump when extraction layout/validation changes; invalidates old markers. */
    private const val RUNTIME_VERSION = 5
    private const val MARKER = ".extracted_v$RUNTIME_VERSION"

    fun ensureExtracted(context: Context): Paths = synchronized(lock) {
        val runtimeDir = File(context.filesDir, "native_runtime")
        val usrLibDir = File(runtimeDir, "usr/lib")
        val binDir = File(runtimeDir, "bin")

        val nativeDir = context.applicationInfo.nativeLibraryDir
        // /system/lib64 on 64-bit, /system/lib on 32-bit. Using the wrong one
        // silently makes support-library resolution fail on 32-bit devices.
        val systemLibDir = if (Process.is64Bit()) "/system/lib64" else "/system/lib"
        val ldLibraryPath = listOf(
            usrLibDir.absolutePath,
            nativeDir,
            systemLibDir
        ).joinToString(":")
        val defaultEnv = mapOf(
            "LD_LIBRARY_PATH" to ldLibraryPath,
            "OPENSSL_CONF" to "/dev/null"
        )

        val ffmpegSource = File(nativeDir, "libffmpeg.so")
        val nodeSource = File(nativeDir, "libnode.so")
        val ffmpegPath = ffmpegSource.absolutePath
        val nodePath = nodeSource.absolutePath

        val marker = File(runtimeDir, MARKER)
        if (isRuntimeReady(runtimeDir, usrLibDir, binDir, marker)) {
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

        // Extract into a staging directory and validate it BEFORE touching the
        // current runtime. A corrupt/partial update must never destroy a working
        // runtime, and a marker must never claim a half-extracted tree is ready.
        val stagingDir = File(context.filesDir, "native_runtime_staging")
        var extractionFailed = false
        try {
            Logger.d(TAG, "Extracting native runtime packages (ffmpeg & node)...")
            stagingDir.deleteRecursively()
            File(stagingDir, "usr/lib").mkdirs()
            File(stagingDir, "bin").mkdirs()

            if (!isElf(ffmpegSource)) {
                throw IOException("$ffmpegPath is not an ELF shared object")
            }
            if (!isElf(nodeSource)) {
                throw IOException("$nodePath is not an ELF shared object")
            }

            extractZipLib(File(nativeDir, "libffmpeg.zip.so"), stagingDir)
            extractZipLib(File(nativeDir, "libnode.zip.so"), stagingDir)

            val stagingUsrLib = File(stagingDir, "usr/lib")
            resolveSymlinkStubs(stagingUsrLib)

            val stagingBin = File(stagingDir, "bin")
            createSymlinkOrCopy(nodeSource, File(stagingBin, "node"))
            createSymlinkOrCopy(ffmpegSource, File(stagingBin, "ffmpeg"))

            if (stagingUsrLib.listFiles()?.isEmpty() != false) {
                throw IOException("Native runtime archive contained no support libraries")
            }

            File(stagingDir, MARKER).writeText(
                "version=$RUNTIME_VERSION\nabi=${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}\n"
            )

            // Promote atomically (same filesystem): keep the old tree until the
            // staged one has replaced it successfully.
            val backupDir = File(context.filesDir, "native_runtime_old")
            backupDir.deleteRecursively()
            if (runtimeDir.exists() && !runtimeDir.renameTo(backupDir)) {
                runtimeDir.deleteRecursively()
            }
            if (!stagingDir.renameTo(runtimeDir)) {
                if (backupDir.exists()) backupDir.renameTo(runtimeDir)
                throw IOException("Could not promote staged native runtime")
            }
            backupDir.deleteRecursively()
            Logger.d(TAG, "Native runtime extracted successfully to ${runtimeDir.absolutePath}")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed during native runtime extraction", e)
            stagingDir.deleteRecursively()
            extractionFailed = true
        }

        // Surface the failure instead of reporting as-if-success: callers can
        // distinguish a working runtime from a broken one.
        Paths(
            runtimeDir = runtimeDir.absolutePath,
            usrLibDir = usrLibDir.absolutePath,
            binDir = binDir.absolutePath,
            ldLibraryPath = ldLibraryPath,
            ffmpegPath = ffmpegPath,
            nodePath = nodePath,
            environment = defaultEnv,
            extractionFailed = extractionFailed
        )
    }

    private fun isRuntimeReady(
        runtimeDir: File,
        usrLibDir: File,
        binDir: File,
        marker: File
    ): Boolean {
        if (!marker.isFile) return false
        val markerText = runCatching { marker.readText() }.getOrNull() ?: return false
        if (!markerText.contains("version=$RUNTIME_VERSION")) return false
        if (!markerText.contains("abi=${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}")) return false
        return runtimeDir.isDirectory &&
            usrLibDir.isDirectory &&
            usrLibDir.listFiles()?.isNotEmpty() == true &&
            File(binDir, "node").exists() &&
            File(binDir, "ffmpeg").exists()
    }

    /** True for a real ELF shared object (guards against Git LFS pointer text). */
    private fun isElf(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        return runCatching {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 &&
                    magic[0] == 0x7f.toByte() &&
                    magic[1] == 'E'.code.toByte() &&
                    magic[2] == 'L'.code.toByte() &&
                    magic[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }

    private fun extractZipLib(zipFile: File, destDir: File) {
        if (!zipFile.exists()) {
            Logger.w(TAG, "Zip lib not found: ${zipFile.absolutePath}")
            return
        }
        Logger.d(TAG, "Unzipping ${zipFile.name}...")
        val destDirCanonical = destDir.canonicalPath + File.separator
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val destFile = File(destDir, entry.name)
                // Zip-slip guard: reject entries whose resolved path escapes the destination directory.
                if (!destFile.canonicalPath.startsWith(destDirCanonical)) {
                    Logger.w(TAG, "Skipping zip entry outside destination directory: ${entry.name}")
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
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
