package com.socialdl.src.feature.settings.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import com.socialdl.src.core.cookies.ui.COOKIE_CAPTURE_RESULT_KEY
import com.socialdl.src.core.model.Platform
import com.socialdl.src.feature.settings.SettingsScreen

const val SETTINGS_ROUTE = "settings_route"

fun NavController.navigateToSettings(navOptions: NavOptions? = null) {
    navigate(SETTINGS_ROUTE, navOptions)
}

fun NavGraphBuilder.settingsScreen(
    onNavigateBack: () -> Unit,
    onSignIn: (Platform) -> Unit,
    appVersion: String = ""
) {
    composable(route = SETTINGS_ROUTE) { backStackEntry ->
        // The capture screen writes its result here on the pop-back. Read and
        // clear it so a stale key cannot replay a refresh on the next visit;
        // SettingsScreen's ON_RESUME observer performs the actual refresh.
        val captureResult by backStackEntry.savedStateHandle
            .getStateFlow<Boolean?>(COOKIE_CAPTURE_RESULT_KEY, null)
            .collectAsStateWithLifecycle()
        LaunchedEffect(captureResult) {
            if (captureResult != null) {
                backStackEntry.savedStateHandle.remove<Boolean>(COOKIE_CAPTURE_RESULT_KEY)
            }
        }
        SettingsScreen(
            onNavigateBack = onNavigateBack,
            onSignIn = onSignIn,
            appVersion = appVersion
        )
    }
}
