package com.sbcfg.manager.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sbcfg.manager.ui.connections.ConnectionsScreen
import com.sbcfg.manager.ui.dashboard.DashboardScreen
import com.sbcfg.manager.ui.settings.SettingsScreen
import com.sbcfg.manager.ui.setup.SetupScreen
import com.sbcfg.manager.ui.speedtest.SpeedTestScreen

@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String,
    deepLinkUrl: String? = null,
    openUpdateCheck: Boolean = false,
    onUpdateCheckConsumed: () -> Unit = {},
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

    // If the update-check deep link arrives while the user is not on settings
    // (e.g. app was already on dashboard), jump there.
    LaunchedEffect(openUpdateCheck) {
        if (openUpdateCheck &&
            navController.currentDestination?.route != "settings"
        ) {
            navController.navigate("settings")
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
                onOpenSpeedTest = { navController.navigate("speedtest") },
                onOpenConnections = { navController.navigate("connections") },
                onStartVpn = onStartVpn,
                onStopVpn = onStopVpn
            )
        }
        composable("connections") {
            ConnectionsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable("speedtest") {
            SpeedTestScreen(onBack = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                openUpdateCheck = openUpdateCheck,
                onUpdateCheckConsumed = onUpdateCheckConsumed
            )
        }
    }
}
