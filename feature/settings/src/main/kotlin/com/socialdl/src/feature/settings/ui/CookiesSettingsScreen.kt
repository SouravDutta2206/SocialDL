package com.socialdl.src.feature.settings.ui

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.socialdl.src.core.model.Platform
import com.socialdl.src.core.ui.component.rememberToastState
import com.socialdl.src.core.ui.theme.SocialDLAccent
import com.socialdl.src.core.ui.theme.SocialDLButton
import com.socialdl.src.core.ui.theme.SocialDLCard
import com.socialdl.src.core.ui.theme.SocialDLError
import com.socialdl.src.core.ui.theme.SocialDLOnSurface
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceDim
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceVariant
import com.socialdl.src.core.ui.theme.SocialDLSurfaceContainerHigh
import com.socialdl.src.feature.settings.CookieEntryUiState
import com.socialdl.src.feature.settings.SettingsViewModel
import com.socialdl.src.feature.settings.ui.components.SettingsGroupCard
import com.socialdl.src.feature.settings.ui.components.SettingsScreenScaffold
import com.socialdl.src.feature.settings.ui.components.SettingsSectionLabel
import com.socialdl.src.feature.settings.ui.components.SettingsToggleRow
import java.util.Locale

@Composable
fun CookiesSettingsScreen(
    onNavigateBack: () -> Unit,
    onSignIn: (Platform) -> Unit,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val savedEntries by viewModel.savedCookieEntries.collectAsStateWithLifecycle()
    var toastMessage by rememberToastState(viewModel.messages)

    var showAddSheet by remember { mutableStateOf(false) }
    var menuPlatform by remember { mutableStateOf<Platform?>(null) }
    var pendingClear by remember { mutableStateOf<Platform?>(null) }

    // Stored as platform NAME: DocumentsUI can recreate the Activity while picking a file.
    var importTargetName by rememberSaveable { mutableStateOf<String?>(null) }
    var exportTargetName by rememberSaveable { mutableStateOf<String?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshCookieStates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val cookiesFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val platform = importTargetName?.let { name ->
            runCatching { Platform.valueOf(name) }.getOrNull()
        }
        importTargetName = null
        if (uri != null && platform != null) {
            viewModel.importCookies(platform, uri)
        }
    }

    val cookiesExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val platform = exportTargetName?.let { name ->
            runCatching { Platform.valueOf(name) }.getOrNull()
        }
        exportTargetName = null
        if (uri != null && platform != null) {
            viewModel.exportCookies(platform, uri)
        }
    }

    SettingsScreenScaffold(
        title = "Cookies",
        onNavigateBack = onNavigateBack,
        toast = toastMessage,
        onDismissToast = { toastMessage = null },
        modifier = modifier
    ) {
        item {
            SettingsGroupCard {
                SettingsToggleRow(
                    title = "Use cookies",
                    checked = settings.useCookies,
                    onCheckedChange = viewModel::setUseCookies
                )
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { showAddSheet = true },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SocialDLButton,
                        contentColor = SocialDLOnSurface
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("New cookie", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        if (savedEntries.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = SocialDLCard
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cookie,
                            contentDescription = null,
                            tint = SocialDLOnSurfaceDim,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "No cookies saved yet",
                            style = MaterialTheme.typography.titleSmall,
                            color = SocialDLOnSurface
                        )
                        Text(
                            text = "Tap + New cookie to sign in or import",
                            style = MaterialTheme.typography.bodySmall,
                            color = SocialDLOnSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(savedEntries, key = { it.platform.name }) { entry ->
                SavedCookieSection(
                    entry = entry,
                    globalCookiesEnabled = settings.useCookies,
                    menuExpanded = menuPlatform == entry.platform,
                    onToggle = { enabled ->
                        viewModel.setPlatformCookieEnabled(entry.platform, enabled)
                    },
                    onLongPress = { menuPlatform = entry.platform },
                    onDismissMenu = { menuPlatform = null },
                    onExport = {
                        menuPlatform = null
                        exportTargetName = entry.platform.name
                        cookiesExportPicker.launch(
                            "${entry.platform.name.lowercase(Locale.ROOT)}_cookies.txt"
                        )
                    },
                    onRequestDelete = {
                        menuPlatform = null
                        pendingClear = entry.platform
                    }
                )
            }
        }
    }

    if (showAddSheet) {
        AddCookieBottomSheet(
            onDismiss = { showAddSheet = false },
            onSignIn = onSignIn,
            onImportCookies = { platform ->
                importTargetName = platform.name
                cookiesFilePicker.launch(arrayOf("*/*"))
            }
        )
    }

    pendingClear?.let { platform ->
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text("Delete ${platform.displayName} cookies?") },
            text = {
                Text(
                    "Saved cookies for ${platform.displayName} will be removed from this device.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingClear = null
                        viewModel.clearCookies(platform)
                    }
                ) {
                    Text("Delete", color = SocialDLError)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedCookieSection(
    entry: CookieEntryUiState,
    globalCookiesEnabled: Boolean,
    menuExpanded: Boolean,
    onToggle: (Boolean) -> Unit,
    onLongPress: () -> Unit,
    onDismissMenu: () -> Unit,
    onExport: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val ageText = entry.lastRefreshedAt?.let {
        DateUtils.getRelativeTimeSpanString(it).toString()
    }
    val statusText = when {
        !globalCookiesEnabled -> "Paused globally"
        !entry.enabled && ageText != null -> "Disabled · Refreshed $ageText"
        !entry.enabled -> "Disabled"
        ageText != null -> "Refreshed $ageText"
        else -> "Active"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSectionLabel(text = entry.platform.displayName)
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(
                        onClick = {
                            if (globalCookiesEnabled) {
                                onToggle(!entry.enabled)
                            }
                        },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress()
                        }
                    ),
                shape = RoundedCornerShape(16.dp),
                color = SocialDLCard
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (globalCookiesEnabled) 1f else 0.6f)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = entry.originUrl,
                            style = MaterialTheme.typography.titleSmall,
                            color = SocialDLOnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = SocialDLOnSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Switch(
                        checked = entry.enabled,
                        enabled = globalCookiesEnabled,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = SocialDLAccent,
                            checkedThumbColor = SocialDLOnSurface
                        )
                    )
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = onDismissMenu,
                modifier = Modifier.background(SocialDLSurfaceContainerHigh)
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Export cookies.txt",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SocialDLOnSurface
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = SocialDLOnSurfaceVariant
                        )
                    },
                    onClick = onExport
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Delete cookies",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SocialDLError
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = SocialDLError
                        )
                    },
                    onClick = onRequestDelete
                )
            }
        }
    }
}
