package com.socialdl.src

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.socialdl.src.core.ui.theme.SocialDLActivePill
import com.socialdl.src.core.ui.theme.SocialDLBg
import com.socialdl.src.core.ui.theme.SocialDLOnSurface
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceVariant
import com.socialdl.src.core.ui.theme.SocialDLTheme
import com.socialdl.src.navigation.SocialDLNavHost
import com.socialdl.src.navigation.TopLevelDestination
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SocialDLTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                // Hide bottom nav when on Settings screen (pushed on top per spec §1 / mock)
                val showBottomBar = TopLevelDestination.entries.any { destination ->
                    currentDestination?.hierarchy?.any { it.route == destination.route } == true
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = SocialDLBg,
                    bottomBar = {
                        if (showBottomBar) {
                            SocialDLBottomBar(
                                destinations = TopLevelDestination.entries,
                                onNavigateToDestination = { destination ->
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                currentDestinationRoute = currentDestination?.route
                            )
                        }
                    }
                ) { innerPadding ->
                    SocialDLNavHost(
                        navController = navController,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
private fun SocialDLBottomBar(
    destinations: List<TopLevelDestination>,
    onNavigateToDestination: (TopLevelDestination) -> Unit,
    currentDestinationRoute: String?,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier,
        containerColor = SocialDLBg,
        contentColor = SocialDLOnSurface
    ) {
        destinations.forEach { destination ->
            val selected = currentDestinationRoute == destination.route
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigateToDestination(destination) },
                icon = {
                    Icon(
                        imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                        contentDescription = destination.label
                    )
                },
                label = { Text(text = destination.label) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = SocialDLActivePill,
                    selectedIconColor = SocialDLOnSurface,
                    unselectedIconColor = SocialDLOnSurfaceVariant,
                    selectedTextColor = SocialDLOnSurface,
                    unselectedTextColor = SocialDLOnSurfaceVariant
                )
            )
        }
    }
}
