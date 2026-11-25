package com.socialdl.src.feature.settings

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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.socialdl.src.core.cookies.CAPTURABLE_PLATFORMS
import com.socialdl.src.core.model.Platform
import com.socialdl.src.core.ui.theme.SocialDLAccent
import com.socialdl.src.core.ui.theme.SocialDLBg
import com.socialdl.src.core.ui.theme.SocialDLOnSurface
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceVariant

/** Which SettingsRow section is currently expanded (null = none). */
private enum class SettingsSection { GENERAL, ABOUT }

/** Display metadata for one platform's cookie section (panel + clear confirmation). */
private data class CookieSection(
    val platform: Platform,
    val icon: ImageVector,
    val subtitle: String,
    val clearText: String,
    /** Extra guidance shown while signed out; null when the platform needs none. */
    val signedOutHint: String? = null,
)

/** Static copy per capturable platform; the capture table owns order and membership. */
private val sectionByPlatform = mapOf(
    Platform.YOUTUBE to CookieSection(
        platform = Platform.YOUTUBE,
        icon = Icons.Default.Cookie,
        subtitle = "Sign in with WebView, Import cookies.txt, Member access",
        clearText = "Age-restricted and member-only downloads will stop working until you sign in again. Encrypted cookies are deleted from this device.",
    ),
    Platform.INSTAGRAM to CookieSection(
        platform = Platform.INSTAGRAM,
        icon = Icons.Default.AccountCircle,
        subtitle = "Sign in with WebView, Private posts and reels",
        clearText = "Private posts and reels will stop resolving until you sign in again. Encrypted cookies are deleted from this device.",
    ),
    Platform.TWITTER to CookieSection(
        platform = Platform.TWITTER,
        icon = Icons.Default.Tag,
        subtitle = "Sign in with WebView, Protected posts, Import cookies.txt",
        clearText = "Protected and follow-gated content will stop resolving until you sign in again. Encrypted cookies are deleted from this device.",
        signedOutHint = "If sign-in is ever refused with \"temporarily limited your login\", " +
            "wait a few minutes and retry, or log in at x.com in a browser, export " +
            "cookies.txt, and import it below.",
    ),
    Platform.THREADS to CookieSection(
        platform = Platform.THREADS,
        icon = Icons.Default.AlternateEmail,
        subtitle = "Sign in with WebView, Private or login-gated posts, Import cookies.txt",
        clearText = "Private and login-gated Threads posts will stop resolving until you sign in again. Encrypted cookies are deleted from this device.",
    ),
)

// Order mirrors the capture table, so adding a platform there also places its row.
private val COOKIE_SECTIONS: List<CookieSection> = CAPTURABLE_PLATFORMS.map { platform ->
    sectionByPlatform.getValue(platform)
}

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onSignIn: (Platform) -> Unit = {},
    appVersion: String = "",
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val cookieStates by viewModel.cookieStates.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var expandedSection by remember { mutableStateOf<SettingsSection?>(null) }
    var expandedCookie by remember { mutableStateOf<Platform?>(null) }
    var pendingClear by remember { mutableStateOf<Platform?>(null) }

    /**
     * Expand/collapse a non-cookie accordion. Opening any of these collapses an
     * open cookie panel so only one section is ever expanded.
     */
    fun toggleSection(section: SettingsSection) {
        expandedCookie = null
        expandedSection = if (expandedSection == section) null else section
    }
    // Stored as the platform NAME: DocumentsUI can destroy/recreate the activity, and
    // a lost target would drop the picked cookies.txt without importing it.
    var importTargetName by rememberSaveable { mutableStateOf<String?>(null) }

    // Jar state is file-stamp based (no watcher), so the panels re-stat on every
    // ON_RESUME: this covers both the pop-back from a capture round-trip and a
    // return from DocumentsUI (cookies import), including predictive-back that
    // never recomposes this screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshCookieStates()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // One cookies.txt picker for every platform (§2.6 fallback); the platform that
    // launched it is remembered until the result arrives.
    val cookiesFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val platform = importTargetName?.let { name ->
            runCatching { Platform.valueOf(name) }.getOrNull()
        }
        importTargetName = null
        if (uri != null && platform != null) viewModel.importCookies(platform, uri)
    }

    // Back collapses the expanded panel first.
    BackHandler(enabled = expandedSection != null || expandedCookie != null) {
        expandedSection = null
        expandedCookie = null
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
                        subtitle = "Concurrency, Wi-Fi only, H.264 transcode",
                        expanded = expandedSection == SettingsSection.GENERAL,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            toggleSection(SettingsSection.GENERAL)
                        }
                    )
                }
                if (expandedSection == SettingsSection.GENERAL) {
                    item {
                        GeneralSettingsPanel(
                            uiState = uiState,
                            onMaxConcurrentChanged = viewModel::setMaxConcurrentDownloads,
                            onWifiOnlyChanged = viewModel::setWifiOnlyDownloads,
                            onForceH264Changed = viewModel::setForceH264Transcode
                        )
                    }
                }

                // One row + panel per capturable platform (§2.6). Order and membership
                // come from CAPTURABLE_PLATFORMS, not another copy of this block.
                COOKIE_SECTIONS.forEach { section ->
                    val state = cookieStates[section.platform] ?: SettingsViewModel.CookiesUiState()
                    item(key = "cookie_${section.platform}") {
                        SettingsRow(
                            icon = section.icon,
                            title = "${section.platform.displayName} cookies",
                            subtitle = section.subtitle,
                            expanded = expandedCookie == section.platform,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                expandedSection = null
                                expandedCookie =
                                    if (expandedCookie == section.platform) null else section.platform
                            }
                        )
                    }
                    if (expandedCookie == section.platform) {
                        item(key = "cookie_panel_${section.platform}") {
                            CookiesSettingsPanel(
                                section = section,
                                state = state,
                                onSignIn = { onSignIn(section.platform) },
                                onImport = {
                                    importTargetName = section.platform.name
                                    cookiesFilePicker.launch(arrayOf("*/*"))
                                },
                                onClear = { pendingClear = section.platform }
                            )
                        }
                    }
                }

                item {
                    SettingsRow(
                        icon = Icons.Default.Info,
                        title = "About",
                        subtitle = "SocialDL $appVersion",
                        expanded = expandedSection == SettingsSection.ABOUT,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            toggleSection(SettingsSection.ABOUT)
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

                // Local-only debug smoke test trigger (absent in release / clones without ExtractorSmokeTests).
                item {
                    val smokeTests = remember {
                        runCatching { Class.forName("com.socialdl.src.ExtractorSmokeTests") }.getOrNull()
                    }
                    if (smokeTests != null) {
                        val app = LocalContext.current.applicationContext as? android.app.Application
                        var triggered by remember {
                            mutableStateOf(
                                runCatching {
                                    smokeTests.getMethod("hasTriggered").invoke(null) as? Boolean
                                }.getOrNull() == true
                            )
                        }
                        OutlinedButton(
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        ) {
                            Text(if (triggered) "Smoke Tests Ran (Restart to Re-run)" else "Run Smoke Tests")
                        }
                    }
                }
            }
        }
    }

    // §2.6: Clear cookies is a destructive action — confirmation dialog first.
    pendingClear?.let { platform ->
        val section = COOKIE_SECTIONS.first { it.platform == platform }
        ClearCookiesConfirmDialog(
            title = "Clear ${platform.displayName} cookies?",
            text = section.clearText,
            onDismiss = { pendingClear = null },
            onConfirm = {
                pendingClear = null
                viewModel.clearCookies(platform)
            }
        )
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

/** §2.6 Settings panel for one platform: status, sign-in, import, destructive clear. */
@Composable
private fun CookiesSettingsPanel(
    section: CookieSection,
    state: SettingsViewModel.CookiesUiState,
    onSignIn: () -> Unit,
    onImport: () -> Unit,
    onClear: () -> Unit
) {
    val present = state.present
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SocialDLBg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = when {
                present && state.lastRefreshedAt != null ->
                    "Signed in · refreshed ${relativeCookieAge(state.lastRefreshedAt)}"
                present -> "Signed in"
                else -> "Not signed in"
            },
            style = MaterialTheme.typography.titleSmall,
            color = SocialDLOnSurface
        )
        Text(
            text = if (present) {
                "These cookies keep the extractor signed in for protected or gated content. The jar is encrypted at rest."
            } else {
                "Sign in once with the WebView, or import a browser-exported cookies.txt. Encrypted at rest."
            },
            style = MaterialTheme.typography.bodySmall,
            color = SocialDLOnSurfaceVariant
        )

        if (!present) {
            section.signedOutHint?.let { hint ->
                Text(hint, style = MaterialTheme.typography.bodySmall, color = SocialDLOnSurfaceVariant)
            }
        }

        Button(
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SocialDLAccent,
                contentColor = SocialDLOnSurface
            )
        ) {
            Text(if (present) "Sign in again" else "Sign in with ${section.platform.displayName}")
        }

        OutlinedButton(
            onClick = onImport,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Import cookies.txt")
        }

        // Clear is a destructive action on something that exists — with no stored
        // jar there is nothing to clear, so the button only shows while signed in.
        if (present) {
            TextButton(
                onClick = onClear,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Clear cookies", color = SocialDLAccent)
            }
        }

        state.actionMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = SocialDLOnSurfaceVariant)
        }
    }
}

@Composable
private fun ClearCookiesConfirmDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Clear", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun relativeCookieAge(timestampMillis: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(timestampMillis).toString()

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
            text = "Downloads are saved to Movies/SocialDL, Music/SocialDL, and Pictures/SocialDL.",
            style = MaterialTheme.typography.bodySmall,
            color = SocialDLOnSurfaceVariant
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
