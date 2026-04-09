package com.sbcfg.manager.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sbcfg.manager.ui.main.MainScreen
import com.sbcfg.manager.ui.setup.SetupScreen

@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String,
    onScanQr: (() -> Unit)? = null,
    deepLinkUrl: String? = null,
    onRequestVpnPermission: () -> Unit = {},
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
                    navController.navigate("main") {
                        popUpTo("setup") { inclusive = true }
                    }
                },
                onScanQr = onScanQr ?: {},
                deepLinkUrl = deepLinkUrl
            )
        }
        composable("main") {
            MainScreen(
                onRequestVpnPermission = onRequestVpnPermission,
                onStartVpn = onStartVpn,
                onStopVpn = onStopVpn
            )
        }
    }
}
