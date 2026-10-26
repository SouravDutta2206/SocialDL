package com.socialdl.src.core.cookies.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import com.socialdl.src.core.cookies.ui.CookieCaptureScreen
import com.socialdl.src.core.model.Platform

const val COOKIE_CAPTURE_ROUTE = "cookie_capture_route"
const val INSTAGRAM_COOKIE_CAPTURE_ROUTE = "instagram_cookie_capture_route"

/**
 * SavedStateHandle key the capture screen uses to hand `success: Boolean` back to
 * the destination it was launched from (§2.6 recapture flow; §2.7 settings screen).
 */
const val COOKIE_CAPTURE_RESULT_KEY = "cookie_capture_result"

fun NavController.navigateToCookieCapture(navOptions: NavOptions? = null) {
    navigate(COOKIE_CAPTURE_ROUTE, navOptions)
}

fun NavController.navigateToInstagramCookieCapture(navOptions: NavOptions? = null) {
    navigate(INSTAGRAM_COOKIE_CAPTURE_ROUTE, navOptions)
}

/**
 * Registers the full-screen YouTube capture route. On completing/cancelling, the
 * result is written to the previous back stack entry's SavedStateHandle and the
 * capture screen pops itself off.
 */
fun NavGraphBuilder.cookieCaptureScreen(navController: NavController) {
    composable(route = COOKIE_CAPTURE_ROUTE) {
        CookieCaptureScreen(
            platform = Platform.YOUTUBE,
            onResult = { success -> publishResult(navController, success) }
        )
    }
}

/** Instagram's capture route — same screen, gallery-dl cookie format. */
fun NavGraphBuilder.instagramCookieCaptureScreen(navController: NavController) {
    composable(route = INSTAGRAM_COOKIE_CAPTURE_ROUTE) {
        CookieCaptureScreen(
            platform = Platform.INSTAGRAM,
            onResult = { success -> publishResult(navController, success) }
        )
    }
}

private fun publishResult(navController: NavController, success: Boolean) {
    navController.previousBackStackEntry
        ?.savedStateHandle
        ?.set(COOKIE_CAPTURE_RESULT_KEY, success)
    navController.popBackStack()
}
