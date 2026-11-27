package com.socialdl.src.feature.settings.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.settings.DEFAULT_AUDIO_FOLDER_RELATIVE
import com.socialdl.src.core.settings.DEFAULT_IMAGE_FOLDER_RELATIVE
import com.socialdl.src.core.settings.DEFAULT_INTERNAL_STORAGE_ROOT
import com.socialdl.src.core.settings.DEFAULT_VIDEO_FOLDER_RELATIVE
import com.socialdl.src.core.settings.formatFolderDisplayPath
import com.socialdl.src.core.ui.component.rememberToastState
import com.socialdl.src.feature.settings.SettingsViewModel
import com.socialdl.src.feature.settings.ui.components.SettingsActionRow
import com.socialdl.src.feature.settings.ui.components.SettingsCardDivider
import com.socialdl.src.feature.settings.ui.components.SettingsGroupCard
import com.socialdl.src.feature.settings.ui.components.SettingsScreenScaffold
import com.socialdl.src.feature.settings.ui.components.SettingsSectionLabel
import com.socialdl.src.feature.settings.ui.components.SettingsToggleRow

@Composable
fun FoldersSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    var toastMessage by rememberToastState(viewModel.messages)
    val context = LocalContext.current
    val internalStorageRoot = remember {
        runCatching { Environment.getExternalStorageDirectory().absolutePath }
            .getOrDefault(DEFAULT_INTERNAL_STORAGE_ROOT)
    }

    // Stored as kind NAME: DocumentsUI can recreate the Activity while picking a folder.
    var pickTargetKindName by rememberSaveable { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val kind = pickTargetKindName?.let { name ->
            runCatching { FormatKind.valueOf(name) }.getOrNull()
        }
        pickTargetKindName = null
        if (uri != null && kind != null) {
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            if (persisted.isFailure) {
                viewModel.showMessage("Couldn't persist folder permission", isError = true)
            } else {
                viewModel.setFolderUri(kind, uri.toString())
            }
        }
    }

    fun openFolderPicker(kind: FormatKind, currentUri: String?) {
        pickTargetKindName = kind.name
        folderPicker.launch(currentUri?.let(Uri::parse))
    }

    SettingsScreenScaffold(
        title = "Folders",
        onNavigateBack = onNavigateBack,
        toast = toastMessage,
        onDismissToast = { toastMessage = null },
        modifier = modifier
    ) {
        item {
            SettingsSectionLabel(text = "Download folders")
            SettingsGroupCard {
                SettingsActionRow(
                    icon = Icons.Default.Audiotrack,
                    title = "Audio folder",
                    subtitle = formatFolderDisplayPath(
                        savedUriOrPath = settings.audioFolderUri,
                        defaultRelativePath = DEFAULT_AUDIO_FOLDER_RELATIVE,
                        internalStorageRoot = internalStorageRoot
                    ),
                    trailingIcon = Icons.Default.ChevronRight,
                    onClick = { openFolderPicker(FormatKind.AUDIO, settings.audioFolderUri) }
                )
                SettingsCardDivider()
                SettingsActionRow(
                    icon = Icons.Default.Videocam,
                    title = "Video folder",
                    subtitle = formatFolderDisplayPath(
                        savedUriOrPath = settings.videoFolderUri,
                        defaultRelativePath = DEFAULT_VIDEO_FOLDER_RELATIVE,
                        internalStorageRoot = internalStorageRoot
                    ),
                    trailingIcon = Icons.Default.ChevronRight,
                    onClick = { openFolderPicker(FormatKind.VIDEO, settings.videoFolderUri) }
                )
                SettingsCardDivider()
                SettingsActionRow(
                    icon = Icons.Default.Image,
                    title = "Image folder",
                    subtitle = formatFolderDisplayPath(
                        savedUriOrPath = settings.imageFolderUri,
                        defaultRelativePath = DEFAULT_IMAGE_FOLDER_RELATIVE,
                        internalStorageRoot = internalStorageRoot
                    ),
                    trailingIcon = Icons.Default.ChevronRight,
                    onClick = { openFolderPicker(FormatKind.IMAGE, settings.imageFolderUri) }
                )
            }
        }

        item {
            SettingsSectionLabel(text = "Storage & filenames")
            SettingsGroupCard {
                SettingsToggleRow(
                    title = "Check for available storage",
                    description = "Check available space before starting single or batch downloads",
                    checked = settings.checkAvailableStorage,
                    onCheckedChange = viewModel::setCheckAvailableStorage
                )
                SettingsCardDivider()
                SettingsToggleRow(
                    title = "Trim filenames",
                    description = "Truncate filenames longer than 40 characters",
                    checked = settings.trimFilenames,
                    onCheckedChange = viewModel::setTrimFilenames
                )
            }
        }
    }
}
