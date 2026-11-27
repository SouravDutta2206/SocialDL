package com.socialdl.src.feature.settings.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.socialdl.src.core.ui.component.rememberToastState
import com.socialdl.src.core.ui.theme.SocialDLButton
import com.socialdl.src.core.ui.theme.SocialDLOnSurface
import com.socialdl.src.feature.settings.SettingsViewModel
import com.socialdl.src.feature.settings.ui.components.SettingsActionRow
import com.socialdl.src.feature.settings.ui.components.SettingsCardDivider
import com.socialdl.src.feature.settings.ui.components.SettingsCategoryRow
import com.socialdl.src.feature.settings.ui.components.SettingsGroupCard
import com.socialdl.src.feature.settings.ui.components.SettingsScreenScaffold
import com.socialdl.src.feature.settings.ui.components.SettingsSectionLabel

private const val SOURCE_CODE_URL = "https://github.com/SouravDutta2206/SocialDL"
private const val LICENSE_NAME = "MIT"

@Composable
fun SettingsHomeScreen(
    onNavigateBack: () -> Unit,
    onOpenFolders: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenCookies: () -> Unit,
    packageName: String,
    appVersion: String,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var toastMessage by rememberToastState(viewModel.messages)
    val effectivePackageName = packageName.ifBlank { context.packageName }
    val packageSubtitle = remember(effectivePackageName, appVersion) {
        if (appVersion.isNotBlank()) "$effectivePackageName · v$appVersion" else effectivePackageName
    }

    SettingsScreenScaffold(
        title = "Settings",
        isRoot = true,
        onNavigateBack = onNavigateBack,
        toast = toastMessage,
        onDismissToast = { toastMessage = null },
        modifier = modifier
    ) {
        item {
            SettingsGroupCard {
                SettingsCategoryRow(
                    icon = Icons.Default.Folder,
                    title = "Folders",
                    subtitle = "Audio, Video, Image folders, Storage",
                    onClick = onOpenFolders
                )
                SettingsCardDivider()
                SettingsCategoryRow(
                    icon = Icons.Default.Download,
                    title = "Downloads",
                    subtitle = "Wi-Fi only, Concurrency, Transcode",
                    onClick = onOpenDownloads
                )
                SettingsCardDivider()
                SettingsCategoryRow(
                    icon = Icons.Default.Cookie,
                    title = "Cookies",
                    subtitle = "Site sessions, Sign in, Import cookies.txt",
                    onClick = onOpenCookies
                )
            }
        }

        // Temporary debug smoke test trigger (absent in release / clones without ExtractorSmokeTests).
        item {
            val smokeTests = remember {
                runCatching { Class.forName("com.socialdl.src.ExtractorSmokeTests") }.getOrNull()
            }
            if (smokeTests != null) {
                val app = context.applicationContext as? android.app.Application
                var triggered by remember {
                    mutableStateOf(
                        runCatching {
                            smokeTests.getMethod("hasTriggered").invoke(null) as? Boolean
                        }.getOrNull() == true
                    )
                }
                Button(
                    enabled = !triggered,
                    onClick = {
                        if (!triggered && app != null) {
                            triggered = true
                            runCatching {
                                smokeTests
                                    .getMethod("trigger", android.app.Application::class.java)
                                    .invoke(null, app)
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SocialDLButton,
                        contentColor = SocialDLOnSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Text(if (triggered) "Smoke Tests Ran (Restart to Re-run)" else "Run Smoke Tests")
                }
            }
        }

        item {
            SettingsSectionLabel(text = "About")
            SettingsGroupCard {
                SettingsActionRow(
                    icon = Icons.Default.Apps,
                    title = "Package name",
                    subtitle = packageSubtitle,
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Package name", effectivePackageName))
                        viewModel.showMessage("Package name copied")
                    }
                )
                SettingsCardDivider()
                SettingsActionRow(
                    icon = Icons.Default.Description,
                    title = "License",
                    subtitle = LICENSE_NAME,
                    onClick = null
                )
                SettingsCardDivider()
                SettingsActionRow(
                    icon = Icons.Default.Code,
                    title = "Source code",
                    subtitle = SOURCE_CODE_URL,
                    trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_CODE_URL))
                            )
                        }.onFailure {
                            viewModel.showMessage("No browser app available", isError = true)
                        }
                    }
                )
            }
        }
    }
}
