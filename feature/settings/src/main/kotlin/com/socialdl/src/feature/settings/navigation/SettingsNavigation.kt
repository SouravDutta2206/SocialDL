package com.socialdl.src.feature.settings.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.socialdl.src.core.cookies.ui.COOKIE_CAPTURE_RESULT_KEY
import com.socialdl.src.core.model.Platform
import com.socialdl.src.feature.settings.SettingsViewModel
import com.socialdl.src.feature.settings.ui.CookiesSettingsScreen
import com.socialdl.src.feature.settings.ui.DownloadsSettingsScreen
import com.socialdl.src.feature.settings.ui.FoldersSettingsScreen
import com.socialdl.src.feature.settings.ui.SettingsHomeScreen

const val SETTINGS_ROUTE = "settings_route"

private const val SETTINGS_HOME_ROUTE = "settings_home"
private const val SETTINGS_FOLDERS_ROUTE = "settings_folders"
private const val SETTINGS_DOWNLOADS_ROUTE = "settings_downloads"
private const val SETTINGS_COOKIES_ROUTE = "settings_cookies"

fun NavController.navigateToSettings(navOptions: NavOptions? = null) {
    navigate(SETTINGS_ROUTE, navOptions)
}

fun NavGraphBuilder.settingsScreen(
    navController: NavController,
    onNavigateBack: () -> Unit,
    onSignIn: (Platform) -> Unit,
    packageName: String = "",
    appVersion: String = "",
) {
    navigation(
        startDestination = SETTINGS_HOME_ROUTE,
        route = SETTINGS_ROUTE
    ) {
        composable(route = SETTINGS_HOME_ROUTE) { backStackEntry ->
            val viewModel = settingsGraphViewModel(navController, backStackEntry)
            SettingsHomeScreen(
                onNavigateBack = onNavigateBack,
                onOpenFolders = { navController.navigate(SETTINGS_FOLDERS_ROUTE) },
                onOpenDownloads = { navController.navigate(SETTINGS_DOWNLOADS_ROUTE) },
                onOpenCookies = { navController.navigate(SETTINGS_COOKIES_ROUTE) },
                packageName = packageName,
                appVersion = appVersion,
                viewModel = viewModel
            )
        }

        composable(route = SETTINGS_FOLDERS_ROUTE) { backStackEntry ->
            val viewModel = settingsGraphViewModel(navController, backStackEntry)
            FoldersSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }

        composable(route = SETTINGS_DOWNLOADS_ROUTE) { backStackEntry ->
            val viewModel = settingsGraphViewModel(navController, backStackEntry)
            DownloadsSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }

        composable(route = SETTINGS_COOKIES_ROUTE) { backStackEntry ->
            // The capture screen writes its result here on the pop-back. Read and
            // clear it so a stale key cannot replay a refresh on the next visit;
            // CookiesSettingsScreen's ON_RESUME observer performs the actual refresh.
            val captureResult by backStackEntry.savedStateHandle
                .getStateFlow<Boolean?>(COOKIE_CAPTURE_RESULT_KEY, null)
                .collectAsStateWithLifecycle()
            LaunchedEffect(captureResult) {
                if (captureResult != null) {
                    backStackEntry.savedStateHandle.remove<Boolean>(COOKIE_CAPTURE_RESULT_KEY)
                }
            }
            val viewModel = settingsGraphViewModel(navController, backStackEntry)
            CookiesSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onSignIn = onSignIn,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun settingsGraphViewModel(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
): SettingsViewModel {
    val graphEntry = remember(backStackEntry) {
        navController.getBackStackEntry(SETTINGS_ROUTE)
    }
    return hiltViewModel(graphEntry)
}
