package com.socialdl.src.feature.home.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import com.socialdl.src.feature.home.HomeScreen

const val HOME_ROUTE = "home_route"

fun NavController.navigateToHome(navOptions: NavOptions? = null) {
    navigate(HOME_ROUTE, navOptions)
}

fun NavGraphBuilder.homeScreen(
    onNavigateToSettings: () -> Unit
) {
    composable(route = HOME_ROUTE) {
        HomeScreen(onNavigateToSettings = onNavigateToSettings)
    }
}
