package com.socialdl.src.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.socialdl.src.core.ui.theme.SocialDLAccent
import com.socialdl.src.core.ui.theme.SocialDLBg
import com.socialdl.src.core.ui.theme.SocialDLOnSurface
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceVariant

/** Which SettingsRow section is currently expanded (null = none). */
private enum class SettingsSection { GENERAL, FOLDERS, COOKIES, LOGS, ABOUT }

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    appVersion: String = "",
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var expandedSection by remember { mutableStateOf<SettingsSection?>(null) }

    // Back collapses the expanded panel first.
    BackHandler(enabled = expandedSection != null) {
        expandedSection = null
    }

    // SAF folder pickers; picked tree URIs are persisted in prefs (storage only —
    // the download pipeline consumes them later, per the deferred plan item).
    val videoFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.setVideoFolder(it)
        }
    }
    val audioFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.setAudioFolder(it)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SocialDLBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            // Back button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = SocialDLOnSurface
                    )
                }
            }

            // Large Title matching mock
            Text(
                text = "Settings",
                style = MaterialTheme.typography.displayLarge,
                color = SocialDLOnSurface,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            // Settings List
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    SettingsRow(
                        icon = Icons.Default.Settings,
                        title = "General",
                        subtitle = "Theme, Preferred search engine, Downloads",
                        expanded = expandedSection == SettingsSection.GENERAL,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            expandedSection =
                                if (expandedSection == SettingsSection.GENERAL) null
                                else SettingsSection.GENERAL
                        }
                    )
                }
                if (expandedSection == SettingsSection.GENERAL) {
                    item {
                        GeneralSettingsPanel(
                            uiState = uiState,
                            onCycleTheme = viewModel::onCycleTheme,
                            onCycleSearchEngine = viewModel::onCycleSearchEngine,
                            onMaxConcurrentChanged = viewModel::setMaxConcurrentDownloads,
                            onWifiOnlyChanged = viewModel::setWifiOnlyDownloads,
                            onForceH264Changed = viewModel::setForceH264Transcode
                        )
                    }
                }

                item {
                    SettingsRow(
                        icon = Icons.Default.Folder,
                        title = "Folders",
                        subtitle = "Audio folder, Video folder, Custom command folder",
                        expanded = expandedSection == SettingsSection.FOLDERS,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            expandedSection =
                                if (expandedSection == SettingsSection.FOLDERS) null
                                else SettingsSection.FOLDERS
                        }
                    )
                }
                if (expandedSection == SettingsSection.FOLDERS) {
                    item {
                        FoldersSettingsPanel(
                            videoFolderName = uiState.videoFolderUri.toFolderDisplayName(),
                            audioFolderName = uiState.audioFolderUri.toFolderDisplayName(),
                            onPickVideoFolder = { videoFolderPicker.launch(null) },
                            onPickAudioFolder = { audioFolderPicker.launch(null) }
                        )
                    }
                }

                item {
                    SettingsRow(
                        icon = Icons.Default.Cookie,
                        title = "Cookies",
                        subtitle = "Import cookies.txt, Browser cookies sync, Member access",
                        expanded = expandedSection == SettingsSection.COOKIES,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            expandedSection =
                                if (expandedSection == SettingsSection.COOKIES) null
                                else SettingsSection.COOKIES
                        }
                    )
                }
                if (expandedSection == SettingsSection.COOKIES) {
                    item { ComingSoonPanel(text = "Cookie import lands with the core:cookies module.") }
                }

                item {
                    SettingsRow(
                        icon = Icons.Default.Description,
                        title = "Logs",
                        subtitle = "Application logs, yt-dlp terminal output, Debug info",
                        expanded = expandedSection == SettingsSection.LOGS,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            expandedSection =
                                if (expandedSection == SettingsSection.LOGS) null
                                else SettingsSection.LOGS
                        }
                    )
                }
                if (expandedSection == SettingsSection.LOGS) {
                    item { ComingSoonPanel(text = "Log viewer is coming in a future update.") }
                }

                item {
                    SettingsRow(
                        icon = Icons.Default.Info,
                        title = "About",
                        subtitle = "SocialDL $appVersion, GitHub, License, Check for updates",
                        expanded = expandedSection == SettingsSection.ABOUT,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            expandedSection =
                                if (expandedSection == SettingsSection.ABOUT) null
                                else SettingsSection.ABOUT
                        }
                    )
                }
                if (expandedSection == SettingsSection.ABOUT) {
                    item {
                        AboutPanel(
                            appVersion = appVersion.ifBlank { "unknown" }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = true, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = SocialDLBg
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = SocialDLOnSurface,
                modifier = Modifier.size(24.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = SocialDLOnSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SocialDLOnSurfaceVariant
                )
            }

            Text(
                text = if (expanded) "Hide" else "Expand",
                style = MaterialTheme.typography.labelSmall,
                color = SocialDLAccent
            )
        }
    }
}

@Composable
private fun GeneralSettingsPanel(
    uiState: SettingsUiState,
    onCycleTheme: () -> Unit,
    onCycleSearchEngine: () -> Unit,
    onMaxConcurrentChanged: (Int) -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onForceH264Changed: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SocialDLBg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        SubSettingRow(
            label = "Theme",
            value = uiState.theme.name.lowercase().replaceFirstChar { it.uppercase() },
            onClick = onCycleTheme
        )
        SubSettingRow(
            label = "Preferred search engine",
            value = uiState.searchEngine.name.lowercase().replaceFirstChar { it.uppercase() },
            onClick = onCycleSearchEngine
        )

        // Max concurrent downloads slider (persisted on release)
        var sliderValue by remember(uiState.maxConcurrentDownloads) {
            mutableFloatStateOf(uiState.maxConcurrentDownloads.toFloat())
        }
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Text(
                text = "Max concurrent downloads: ${sliderValue.toInt()}",
                style = MaterialTheme.typography.titleSmall,
                color = SocialDLOnSurface
            )
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                valueRange = 1f..10f,
                steps = 8,
                onValueChangeFinished = { onMaxConcurrentChanged(sliderValue.toInt()) }
            )
        }

        ToggleRow(
            label = "Wi-Fi only downloads",
            checked = uiState.wifiOnlyDownloads,
            onCheckedChange = onWifiOnlyChanged
        )
        ToggleRow(
            label = "Force H.264 transcode",
            checked = uiState.forceH264Transcode,
            onCheckedChange = onForceH264Changed
        )
    }
}

@Composable
private fun FoldersSettingsPanel(
    videoFolderName: String,
    audioFolderName: String,
    onPickVideoFolder: () -> Unit,
    onPickAudioFolder: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SocialDLBg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        SubSettingRow(
            label = "Video folder",
            value = videoFolderName,
            onClick = onPickVideoFolder
        )
        SubSettingRow(
            label = "Audio folder",
            value = audioFolderName,
            onClick = onPickAudioFolder
        )
    }
}

@Composable
private fun ComingSoonPanel(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SocialDLBg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "Coming soon",
            style = MaterialTheme.typography.titleSmall,
            color = SocialDLOnSurface
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = SocialDLOnSurfaceVariant
        )
    }
}

@Composable
private fun AboutPanel(appVersion: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SocialDLBg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = "SocialDL $appVersion",
            style = MaterialTheme.typography.titleSmall,
            color = SocialDLOnSurface
        )
        Text(
            text = "Open-source social media downloader.",
            style = MaterialTheme.typography.bodySmall,
            color = SocialDLOnSurfaceVariant
        )
        Text(
            text = "GitHub · License · Check for updates",
            style = MaterialTheme.typography.bodySmall,
            color = SocialDLOnSurfaceVariant
        )
    }
}

@Composable
private fun SubSettingRow(
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = SocialDLOnSurface
        )
        Text(
            text = "$value ▸",
            style = MaterialTheme.typography.bodySmall,
            color = SocialDLAccent
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = SocialDLOnSurface
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = SocialDLAccent)
        )
    }
}

private fun Uri?.toFolderDisplayName(): String {
    if (this == null) return "Default storage location"
    val segment = lastPathSegment ?: return "Custom folder"
    return segment.substringAfterLast('/').ifBlank { "Custom folder" }
}
