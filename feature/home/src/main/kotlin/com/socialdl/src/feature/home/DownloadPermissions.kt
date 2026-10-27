package com.socialdl.src.feature.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Requests the permissions a download actually needs, at the moment the user
 * starts one (denying is non-fatal):
 *
 * - `POST_NOTIFICATIONS` (API 33+): foreground-service progress and the
 *   completion notification. Asking on first download avoids the "denied before
 *   the user ever saw a download" trap of asking at launch.
 * - `WRITE_EXTERNAL_STORAGE` (API <= 28): required for MediaStore inserts, as
 *   scoped storage only starts at API 29.
 *
 * The returned function takes the action to run once the request resolves.
 */
@Composable
fun rememberDownloadPermissionRequest(): (onReady: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        pendingAction?.invoke()
        pendingAction = null
    }

    return remember(context, launcher) {
        { onReady ->
            val missing = missingDownloadPermissions(context)
            if (missing.isEmpty()) {
                onReady()
            } else {
                pendingAction = onReady
                launcher.launch(missing.toTypedArray())
            }
        }
    }
}

private fun missingDownloadPermissions(context: Context): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !context.isGranted(Manifest.permission.POST_NOTIFICATIONS)
    ) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        !context.isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    ) {
        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
