package com.socialdl.src.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.socialdl.src.BuildConfig
import com.socialdl.src.core.cookies.navigation.cookieCaptureScreen
import com.socialdl.src.core.cookies.navigation.instagramCookieCaptureScreen
import com.socialdl.src.core.cookies.navigation.navigateToCookieCapture
import com.socialdl.src.core.cookies.navigation.navigateToInstagramCookieCapture
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
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        homeScreen(
            onNavigateToSettings = { navController.navigateToSettings() },
            onNavigateToSignInYouTube = { navController.navigateToCookieCapture() }
        )

        downloadsScreen(
            onNavigateBack = { navController.popBackStack() }
        )

        settingsScreen(
            onNavigateBack = { navController.popBackStack() },
            onNavigateToCookieCapture = { navController.navigateToCookieCapture() },
            onNavigateToInstagramCookieCapture = { navController.navigateToInstagramCookieCapture() },
            appVersion = BuildConfig.VERSION_NAME
        )

        // §2.6 — full-screen WebView cookie capture; registered once per platform,
        // launched from both the Home auth-wall card and Settings → Cookies.
        cookieCaptureScreen(navController)

        // Phase 5 — Instagram cookies for gallery-dl.
        instagramCookieCaptureScreen(navController)
    }
}
