package com.socialdl.src.core.runtime

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** An installed helper APK for one component, as reported by PackageManager. */
internal data class HelperPackage(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val nativeLibraryDir: File,
    /** Lowercase SHA-256 of each certificate that signed the installed APK. */
    val signers: Set<String>,
)

/** Queries only [spec]'s helper package name. Returns null when it is not installed. */
internal fun findHelperPackage(context: Context, spec: NativeComponentSpec): HelperPackage? {
    val info = try {
        packageInfoOf(context.packageManager, spec.helperPackage)
    } catch (_: PackageManager.NameNotFoundException) {
        return null
    }
    val nativeLibraryDir = info.applicationInfo?.nativeLibraryDir ?: return null
    return HelperPackage(
        packageName = spec.helperPackage,
        versionName = info.versionName,
        versionCode = versionCodeOf(info),
        nativeLibraryDir = File(nativeLibraryDir),
        signers = signerHashesOf(info),
    )
}

@Suppress("DEPRECATION")
private fun packageInfoOf(packageManager: PackageManager, packageName: String): PackageInfo {
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        packageManager.getPackageInfo(packageName, flags)
    }
}

@Suppress("DEPRECATION")
private fun versionCodeOf(info: PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

/** The APK's own signers, not the certificate rotation lineage. */
@Suppress("DEPRECATION")
private fun signerHashesOf(info: PackageInfo): Set<String> {
    val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.signingInfo?.apkContentsSigners
    } else {
        info.signatures
    }
    return signers.orEmpty().mapTo(HashSet()) { sha256(it.toByteArray()) }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Decides whether an installed helper's signer may be used. Only the signer is checked
 * here; a helper with missing or corrupt files is rejected when its runtime is prepared.
 */
internal object HelperPackageVerifier {

    /**
     * Returns why [helper] must not be used, or null if it may be used.
     *
     * [pinnedSigners] is the comma-separated SHA-256 list from the build. With no pin,
     * debug builds accept any signer (for adb-installed test helpers) and release
     * builds accept none.
     */
    fun rejectionReason(helper: HelperPackage, pinnedSigners: String, debugBuild: Boolean): String? {
        val pins = pinnedSigners.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        return when {
            pins.isEmpty() && debugBuild -> null
            pins.isEmpty() -> "no helper signer pin configured"
            // Every signer of a multi-signed APK must be trusted. `any` let an
            // APK signed by one pinned + one untrusted certificate through.
            helper.signers.isNotEmpty() && helper.signers.all { it in pins } -> null
            else -> "helper signer is not pinned"
        }
    }
}
