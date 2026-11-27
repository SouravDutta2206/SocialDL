package com.socialdl.src.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.socialdl.src.BuildConfig
import com.socialdl.src.core.cookies.ui.cookieCaptureScreen
import com.socialdl.src.core.cookies.ui.navigateToCookieCapture
import com.socialdl.src.feature.downloads.navigation.downloadsScreen
import com.socialdl.src.feature.home.navigation.HOME_ROUTE
import com.socialdl.src.feature.home.navigation.homeScreen
import com.socialdl.src.feature.settings.navigation.navigateToSettings
import com.socialdl.src.feature.settings.navigation.settingsScreen

@Composable
fun SocialDLNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = HOME_ROUTE
) {
    // §2.6 — one capture destination for every platform; it is launched from both the
    // Home auth-wall card and Settings → cookies, which pass the Platform they need.
    val signIn: (com.socialdl.src.core.model.Platform) -> Unit = { platform ->
        navController.navigateToCookieCapture(platform)
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        homeScreen(
            onNavigateToSettings = { navController.navigateToSettings() },
            onNavigateToSignIn = signIn
        )

        downloadsScreen(
            onNavigateBack = { navController.popBackStack() }
        )

        settingsScreen(
            navController = navController,
            onNavigateBack = { navController.popBackStack() },
            onSignIn = signIn,
            packageName = BuildConfig.APPLICATION_ID,
            appVersion = BuildConfig.VERSION_NAME
        )

        cookieCaptureScreen(navController)
    }
}
