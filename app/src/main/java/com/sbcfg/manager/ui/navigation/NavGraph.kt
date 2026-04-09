package com.sbcfg.manager.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sbcfg.manager.ui.dashboard.DashboardScreen
import com.sbcfg.manager.ui.settings.SettingsScreen
import com.sbcfg.manager.ui.setup.SetupScreen

@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String,
    deepLinkUrl: String? = null,
    onStartVpn: (configJson: String?) -> Unit = {},
    onStopVpn: () -> Unit = {}
) {
    // If a deep link arrives while the user is outside the setup screen
    // (e.g. already on dashboard), jump to setup so the URL can be applied.
    LaunchedEffect(deepLinkUrl) {
        if (deepLinkUrl != null &&
            navController.currentDestination?.route != "setup"
        ) {
            navController.navigate("setup")
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("setup") {
            SetupScreen(
                onConfigured = {
                    navController.navigate("dashboard") {
                        popUpTo("setup") { inclusive = true }
                    }
                },
                deepLinkUrl = deepLinkUrl
            )
        }
        composable("dashboard") {
            DashboardScreen(
                onOpenSettings = { navController.navigate("settings") },
                onStartVpn = onStartVpn,
                onStopVpn = onStopVpn
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
