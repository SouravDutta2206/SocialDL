package com.socialdl.src.core.cookies.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import com.socialdl.src.core.cookies.ui.CookieCaptureScreen

const val COOKIE_CAPTURE_ROUTE = "cookie_capture_route"

/**
 * SavedStateHandle key the capture screen uses to hand `success: Boolean` back to
 * the destination it was launched from (§2.6 recapture flow; §2.7 settings screen).
 */
const val COOKIE_CAPTURE_RESULT_KEY = "cookie_capture_result"

fun NavController.navigateToCookieCapture(navOptions: NavOptions? = null) {
    navigate(COOKIE_CAPTURE_ROUTE, navOptions)
}

/**
 * Registers the full-screen capture route. On completing/cancelling, the result is
 * written to the previous back stack entry's SavedStateHandle and the capture
 * screen pops itself off.
 */
fun NavGraphBuilder.cookieCaptureScreen(navController: NavController) {
    composable(route = COOKIE_CAPTURE_ROUTE) {
        CookieCaptureScreen(
            onResult = { success ->
                navController.previousBackStackEntry
                    ?.savedStateHandle
                    ?.set(COOKIE_CAPTURE_RESULT_KEY, success)
                navController.popBackStack()
            }
        )
    }
}
