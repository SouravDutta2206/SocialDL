package com.socialdl.src.feature.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Permissions the app asks for on first launch ([RequestStartupPermissions]):
 *
 * - `POST_NOTIFICATIONS` (API 33+): foreground-service progress and the
 *   completion notification. Denying it is non-fatal — downloads still run.
 * - `WRITE_EXTERNAL_STORAGE` (API <= 28): required for MediaStore inserts, as
 *   scoped storage only starts at API 29. Denying it blocks downloads, because
 *   the export would otherwise fail after the bytes were fetched.
 *
 * The prompt is persisted (`SettingsRepository.startupPermissionsRequested`) so it
 * runs exactly once per install, whatever the user answers.
 */
@Composable
fun RequestStartupPermissions(
    viewModel: StartupPermissionsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // The answer is not cached here: the grant state is re-read whenever a
        // download starts, so a later grant from system settings works immediately.
    }

    LaunchedEffect(Unit) {
        // Compute what is actually missing BEFORE claiming the one-shot prompt.
        // On API 29-32 there is nothing to ask yet (WRITE_EXTERNAL_STORAGE is
        // capped at 28); claiming then burned the flag, so an upgrade to
        // Android 13 never got the POST_NOTIFICATIONS dialog.
        val missing = missingStartupPermissions(context)
        if (missing.isEmpty()) return@LaunchedEffect

        // Claiming happens before the dialog is shown, so a rotation while it is
        // open cannot ask a second time.
        if (!viewModel.claimStartupPrompt()) return@LaunchedEffect
        launcher.launch(missing.toTypedArray())
    }
}

/**
 * Download-time gate.
 *
 * API 29+ uses scoped storage, so this is a no-op. API 24-28 needs legacy
 * storage access for the MediaStore export: when it is missing, show the runtime
 * permission prompt once instead of silently blocking every download. A grant
 * runs the download that was waiting; a denial reports through [onStorageDenied].
 */
@Composable
fun rememberDownloadPermissionGate(
    onStorageDenied: () -> Unit = {},
): (onReady: () -> Unit) -> Unit {
    val context = LocalContext.current
    val currentOnStorageDenied by rememberUpdatedState(onStorageDenied)
    var pendingOnReady by remember { mutableStateOf<(() -> Unit)?>(null) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val onReady = pendingOnReady
        pendingOnReady = null
        if (granted) {
            onReady?.invoke()
        } else {
            currentOnStorageDenied()
        }
    }

    return remember(context, storagePermissionLauncher) {
        { onReady ->
            if (canStartDownload(
                    storageGranted = context.isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    sdkInt = Build.VERSION.SDK_INT,
                )
            ) {
                onReady()
            } else {
                pendingOnReady = onReady
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
}

/**
 * Whether a download may start with the storage permission in its current state.
 *
 * API 29+ uses scoped storage and declares `WRITE_EXTERNAL_STORAGE` with
 * `maxSdkVersion="28"`, so it is never requested and can never gate a download.
 * API 24-28 needs legacy storage access for the MediaStore export.
 */
internal fun canStartDownload(storageGranted: Boolean, sdkInt: Int): Boolean =
    sdkInt > Build.VERSION_CODES.P || storageGranted

/**
 * Permissions the first-launch prompt should ask for, given the platform level and
 * the current grant state. Returns an empty list when nothing needs asking.
 */
internal fun startupPermissions(sdkInt: Int, isGranted: (String) -> Boolean): List<String> = buildList {
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU &&
        !isGranted(Manifest.permission.POST_NOTIFICATIONS)
    ) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (sdkInt <= Build.VERSION_CODES.P &&
        !isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    ) {
        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}

internal fun missingStartupPermissions(context: Context): List<String> =
    startupPermissions(Build.VERSION.SDK_INT) { permission -> context.isGranted(permission) }

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
