package com.socialdl.src.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.socialdl.src.core.settings.MAX_CONCURRENT_DOWNLOADS
import com.socialdl.src.core.settings.MAX_CONCURRENT_FRAGMENTS
import com.socialdl.src.core.settings.MIN_CONCURRENT_DOWNLOADS
import com.socialdl.src.core.settings.MIN_CONCURRENT_FRAGMENTS
import com.socialdl.src.feature.settings.SettingsViewModel
import com.socialdl.src.feature.settings.ui.components.SettingsCardDivider
import com.socialdl.src.feature.settings.ui.components.SettingsGroupCard
import com.socialdl.src.feature.settings.ui.components.SettingsScreenScaffold
import com.socialdl.src.feature.settings.ui.components.SettingsSectionLabel
import com.socialdl.src.feature.settings.ui.components.SettingsSliderRow
import com.socialdl.src.feature.settings.ui.components.SettingsToggleRow

@Composable
fun DownloadsSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()

    SettingsScreenScaffold(
        title = "Downloads",
        onNavigateBack = onNavigateBack,
        modifier = modifier
    ) {
        item {
            SettingsSectionLabel(text = "Network & compatibility")
            SettingsGroupCard {
                SettingsToggleRow(
                    title = "Wi-Fi only downloads",
                    description = "Downloads content on Wi-Fi only",
                    checked = settings.wifiOnlyDownloads,
                    onCheckedChange = viewModel::setWifiOnlyDownloads
                )
                SettingsCardDivider()
                SettingsToggleRow(
                    title = "Force H.264 transcode",
                    description = "Re-encode incompatible video streams to H.264",
                    checked = settings.forceH264Transcode,
                    onCheckedChange = viewModel::setForceH264Transcode
                )
            }
        }

        item {
            SettingsSectionLabel(text = "Concurrency")
            SettingsGroupCard {
                SettingsSliderRow(
                    title = "Max concurrent fragments",
                    description = "Number of fragments of a DASH/HLS native video to download concurrently",
                    value = settings.maxConcurrentFragments,
                    valueRange = MIN_CONCURRENT_FRAGMENTS.toFloat()..MAX_CONCURRENT_FRAGMENTS.toFloat(),
                    steps = (MAX_CONCURRENT_FRAGMENTS - MIN_CONCURRENT_FRAGMENTS) - 1,
                    onValueChangeFinished = viewModel::setMaxConcurrentFragments
                )
                SettingsCardDivider()
                SettingsSliderRow(
                    title = "Max concurrent downloads",
                    description = "Number of downloads executing at the same time",
                    value = settings.maxConcurrentDownloads,
                    valueRange = MIN_CONCURRENT_DOWNLOADS.toFloat()..MAX_CONCURRENT_DOWNLOADS.toFloat(),
                    steps = (MAX_CONCURRENT_DOWNLOADS - MIN_CONCURRENT_DOWNLOADS) - 1,
                    onValueChangeFinished = viewModel::setMaxConcurrentDownloads
                )
            }
        }
    }
}
