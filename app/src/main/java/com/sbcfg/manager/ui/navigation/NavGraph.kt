package com.sbcfg.manager.ui.navigation

import androidx.compose.runtime.Composable
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
    onScanQr: (() -> Unit)? = null,
    deepLinkUrl: String? = null,
    onStartVpn: (configJson: String?) -> Unit = {},
    onStopVpn: () -> Unit = {}
) {
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
                onScanQr = onScanQr ?: {},
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
