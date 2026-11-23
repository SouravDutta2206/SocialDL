package com.socialdl.src.feature.home.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.socialdl.src.core.cookies.ui.COOKIE_CAPTURE_RESULT_KEY
import com.socialdl.src.core.model.Platform
import com.socialdl.src.feature.home.HomeScreen
import com.socialdl.src.feature.home.HomeViewModel

const val HOME_ROUTE = "home_route"

fun NavGraphBuilder.homeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToSignIn: (Platform) -> Unit,
) {
    composable(route = HOME_ROUTE) { backStackEntry ->
        val viewModel: HomeViewModel = hiltViewModel()

        // §2.6 recapture flow: when a cookie-capture screen (launched from the
        // auth-wall card) pops itself off, it leaves its result on this entry's
        // SavedStateHandle. Reading it as a StateFlow (not once by entry id)
        // catches results delivered after the destination is already composed,
        // and the key is removed so a recomposition/re-entry cannot replay it.
        val captureResult by backStackEntry.savedStateHandle
            .getStateFlow<Boolean?>(COOKIE_CAPTURE_RESULT_KEY, null)
            .collectAsStateWithLifecycle()
        LaunchedEffect(captureResult) {
            val result = captureResult ?: return@LaunchedEffect
            backStackEntry.savedStateHandle.remove<Boolean>(COOKIE_CAPTURE_RESULT_KEY)
            if (result) {
                viewModel.onAuthSignInCompleted()
            }
        }

        HomeScreen(
            onNavigateToSettings = onNavigateToSettings,
            onSignIn = onNavigateToSignIn,
            viewModel = viewModel
        )
    }
}
