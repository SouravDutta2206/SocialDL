package com.socialdl.src.feature.settings.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import com.socialdl.src.feature.settings.SettingsScreen

const val SETTINGS_ROUTE = "settings_route"

fun NavController.navigateToSettings(navOptions: NavOptions? = null) {
    navigate(SETTINGS_ROUTE, navOptions)
}

fun NavGraphBuilder.settingsScreen(
    onNavigateBack: () -> Unit,
    appVersion: String = ""
) {
    composable(route = SETTINGS_ROUTE) {
        SettingsScreen(onNavigateBack = onNavigateBack, appVersion = appVersion)
    }
}
