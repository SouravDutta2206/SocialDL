package com.socialdl.src.feature.home.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import com.socialdl.src.core.cookies.navigation.COOKIE_CAPTURE_RESULT_KEY
import com.socialdl.src.feature.home.HomeScreen
import com.socialdl.src.feature.home.HomeViewModel

const val HOME_ROUTE = "home_route"

fun NavController.navigateToHome(navOptions: NavOptions? = null) {
    navigate(HOME_ROUTE, navOptions)
}

fun NavGraphBuilder.homeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToSignInYouTube: () -> Unit = {},
    onNavigateToSignInInstagram: () -> Unit = {},
) {
    composable(route = HOME_ROUTE) { backStackEntry ->
        val viewModel: HomeViewModel = hiltViewModel()

        // §2.6 recapture flow: when either cookie-capture screen (launched from
        // the auth-wall card) pops itself off, it leaves its result on this entry's
        // SavedStateHandle. A success here auto-retries the URL that hit the wall.
        LaunchedEffect(backStackEntry.id) {
            if (backStackEntry.savedStateHandle.remove<Boolean>(COOKIE_CAPTURE_RESULT_KEY) == true) {
                viewModel.onAuthSignInCompleted()
            }
        }

        HomeScreen(
            onNavigateToSettings = onNavigateToSettings,
            onSignInWithYouTube = onNavigateToSignInYouTube,
            onSignInWithInstagram = onNavigateToSignInInstagram,
            viewModel = viewModel
        )
    }
}
