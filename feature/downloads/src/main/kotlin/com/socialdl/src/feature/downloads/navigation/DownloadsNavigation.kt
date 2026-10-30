package com.socialdl.src.feature.downloads.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.socialdl.src.feature.downloads.DownloadsScreen

const val DOWNLOADS_ROUTE = "downloads_route"

fun NavGraphBuilder.downloadsScreen(
    onNavigateBack: () -> Unit
) {
    composable(route = DOWNLOADS_ROUTE) {
        DownloadsScreen(onNavigateBack = onNavigateBack)
    }
}
